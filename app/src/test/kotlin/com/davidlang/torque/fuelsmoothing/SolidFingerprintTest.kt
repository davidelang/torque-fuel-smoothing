package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SolidFingerprintTest {
    private val pwm = "J1850 PWM(41.6K)"
    /** The van as a connect-time identity read sees it (no VIN, 0100 BF9FB910, 16C1 answers). */
    private val vanObs = Fingerprint.Fp(protocol = pwm, bitmaps = mapOf("0100" to "BF9FB910"), probes = mapOf("ford2216C1" to "ok"))
    private val stored = Fingerprint.Fp(protocol = pwm, bitmaps = mapOf("0100" to "BF9FB910"),
        probes = mapOf("ford2216C1" to "ok", "live:ford_16c1" to "ok"), sessions = 37)
    private val names = mapOf("van" to "van", "rental" to "Rental")

    @Test
    fun test132MarkSolidNeedsProtocolAndExactBitmapAndNoVin() {
        val s = Fingerprint.markSolid(stored, vanObs)!!
        assertEquals("1", s.probes[Fingerprint.SOLID])
        assertEquals("BF9FB910", s.probes[Fingerprint.SOLID_0100])
        assertEquals("ok", s.probes[Fingerprint.SOLID_16C1])
        assertEquals("ok", s.probes["live:ford_16c1"]) // existing keys kept
        assertNull(Fingerprint.markSolid(stored, vanObs.copy(bitmaps = emptyMap())))
        assertNull(Fingerprint.markSolid(stored, vanObs.copy(protocol = null)))
        assertNull(Fingerprint.markSolid(stored, vanObs.copy(vin = "1FTXX0000000X7380")))
        assertNull(Fingerprint.markSolid(stored.copy(protocol = "ISO 15765-4 (CAN 11/500)"), vanObs))
        val back = Fingerprint.fromJson(Fingerprint.toJson(s))
        assertEquals(s.probes, back.probes)
    }

    @Test
    fun test133SolidMatchIsExactOnBusValuesTolerantOnTorqueName() {
        val van = Fingerprint.markSolid(stored, vanObs)!!
        val rental = Fingerprint.Fp(vin = "3N1XX0000000X0000", protocol = "ISO 15765-4 (CAN 11/500)", bitmaps = mapOf("0100" to "BE3FA813"))
        val fps = mapOf("van" to van, "rental" to rental)
        val of: (String) -> String? = { names[it] }
        assertEquals("van", Fingerprint.solidMatch(vanObs, fps, "VAN", of))
        // One 7F0100 reply even after the re-ask: no bitmap, no solid match (full check + prompt instead).
        assertNull(Fingerprint.solidMatch(vanObs.copy(bitmaps = emptyMap()), fps, "van", of))
        assertNull(Fingerprint.solidMatch(vanObs.copy(bitmaps = mapOf("0100" to "BF9FB911")), fps, "van", of))
        assertNull(Fingerprint.solidMatch(vanObs.copy(probes = mapOf("ford2216C1" to "no")), fps, "van", of))
        assertNull(Fingerprint.solidMatch(vanObs.copy(protocol = "SAE J1850 VPW"), fps, "van", of))
        assertNull(Fingerprint.solidMatch(vanObs, fps, "Rental", of))
        assertNull(Fingerprint.solidMatch(vanObs.copy(vin = "1FTXX0000000X7380"), fps, "van", of))
        assertNull(Fingerprint.solidMatch(vanObs, mapOf("van" to stored), "van", of)) // not solid yet
        // Two solid profiles that both match: ambiguous, fall back.
        assertNull(Fingerprint.solidMatch(vanObs, mapOf("van" to van, "van2" to van), "van") { "van" })
    }

    @Test
    fun test134ButtonReadBecomesAnObservation() {
        val p = VehicleProbe("manual", "van", pwm, 0L, 0L, withIdentity = true)
        var mono = 0L
        val replies = mapOf("0902" to listOf("NO DATA"), "0100" to listOf("4100BF9FB910"), "2216C1" to listOf("6216C11D2C"))
        while (!p.finished) { p.step(mono, mono) { _, c -> replies[c] ?: listOf("NO DATA") }; mono += VehicleProbe.GAP_MS }
        val obs = p.observation()
        assertNull(obs.vin)
        assertEquals(mapOf("0100" to "BF9FB910"), obs.bitmaps)
        assertEquals("ok", obs.probes["ford2216C1"])
        assertNotNull(Fingerprint.solidMatch(obs, mapOf("van" to Fingerprint.markSolid(stored, vanObs)!!), "van") { "van" })
    }
}
