package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProbeTest {
    private val pwm = "J1850 PWM(41.6K)"

    /** Van replies as seen in the Oct 16 probe logs and Nov 18 export; first 0100 is the occasional refusal. */
    private fun van(): (String, String) -> List<String>? {
        var first0100 = true
        return { h, c ->
            when (c) {
                "0100" -> if (first0100) { first0100 = false; listOf("7F0100000011") } else listOf("4100BF9FB910")
                "0101" -> listOf("41010007E500")
                "011C" -> listOf("411C01")
                "0902" -> listOf("NO DATA")
                "2216C1" -> if (h == "C410F1") listOf("6216C11D2C") else listOf("NO DATA")
                "22F190", "22F188", "22F111", "22F113" -> if (h == "C410F1") listOf("7F22" + c.drop(2) + "0031") else listOf("NO DATA")
                else -> listOf("NO DATA")
            }
        }
    }

    @Test
    fun test129ProbeIsPacedReasks0100OnceAndUsesAllowedModesOnly() {
        val steps = VehicleProbe.steps(pwm, withIdentity = false)
        assertEquals(listOf("0100", "0101", "011C", "22F190", "22F188", "22F111", "22F113", "ABS:22F190"), steps.map { it.key })
        assertTrue(steps.all { it.cmd.take(2) in setOf("01", "09", "22") })
        assertEquals("C428F1", steps.last().header)
        val p = VehicleProbe("auto", "van", pwm, 1_000L, 0L)
        val sent = ArrayList<Long>()
        val send = van()
        var mono = 0L
        while (!p.finished && mono < 200_000L) {
            p.step(mono, 1_000L + mono) { h, c -> sent.add(mono); send(h, c) }
            mono += 1_000L
        }
        assertTrue(p.finished)
        assertNull(p.abortReason)
        assertEquals(9, sent.size) // 8 steps + one 0100 re-ask
        assertTrue(sent.zipWithNext().all { (a, b) -> b - a >= VehicleProbe.GAP_MS })
        val st = p.stable()
        assertEquals("data:BF9FB910", st["id:0100"])
        assertEquals("data", st["id:0101"]) // monitor status changes: class only
        assertEquals("data:01", st["id:011C"])
        assertEquals("nrc:31", st["id:22F190"])
        assertEquals("nodata", st["id:ABS:22F190"])
        assertEquals(9, p.results.length())
    }

    @Test
    fun test130ClassifyHashesIdentityPayloadsAndCapsTime() {
        assertEquals("nrc:11", VehicleProbe.classify("0902", listOf("7F0902000011")))
        assertEquals("nrc:12", VehicleProbe.classify("228126", listOf("7F2281260012")))
        assertEquals("nodata", VehicleProbe.classify("0120", listOf("NO DATA")))
        assertEquals("none", VehicleProbe.classify("0100", emptyList()))
        assertEquals("data:BF9FB910", VehicleProbe.classify("0100", listOf("41 00 BF 9F B9 10")))
        val p = VehicleProbe("manual", "van", pwm, 0L, 0L, withIdentity = true)
        p.step(0L, 0L) { _, _ -> listOf("4902014641542D3132333435") } // 0902 with a (fake) VIN payload
        val v = p.stable()["id:0902"]!!
        assertTrue(v.startsWith("data#"))
        assertFalse(v.contains("4641542D")) // never the payload itself
        // Hard time cap: nothing is sent after MAX_TOTAL_MS.
        var calls = 0
        assertTrue(p.step(VehicleProbe.MAX_TOTAL_MS + 1, 0L) { _, _ -> calls++; listOf("NO DATA") })
        assertEquals(0, calls)
        assertEquals("time_cap", p.abortReason)
    }

    @Test
    fun test131IdProbeAnswersMergeAndVaryingOnesAreMarked() {
        var f = Fingerprint.Fp(protocol = pwm, probes = mapOf("ford2216C1" to "ok"))
        f = Fingerprint.withIdProbe(f, mapOf("id:0100" to "data:BF9FB910", "id:22F190" to "nrc:31"), complete = false)
        assertNull(f.probes[Fingerprint.ID_PROBE])
        f = Fingerprint.withIdProbe(f, mapOf("id:0100" to "data:BF9FB910", "id:22F190" to "nodata"), complete = true)
        assertEquals("data:BF9FB910", f.probes["id:0100"])
        assertEquals(Fingerprint.VARIES, f.probes["id:22F190"])
        assertEquals(VehicleProbe.VERSION, f.probes[Fingerprint.ID_PROBE])
        val back = Fingerprint.fromJson(Fingerprint.toJson(f))
        assertEquals(f.probes, back.probes)
    }
}
