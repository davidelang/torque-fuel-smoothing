package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TipAndTankTest {
    private val A = TipRules.Action::class

    @Test
    fun test40NoTipRecordMeansNoBillingConnection() {
        assertFalse(TipRules.needsCheck(null))
        assertTrue(TipRules.decide(null, emptyList(), 0L).isEmpty())
        assertTrue(TipRules.needsCheck(TipRules.Record(TipRules.State.STARTED, 0L)))
    }

    @Test
    fun test41TipReconcileAcknowledgesConsumesAndClears() {
        val now = 10_000_000L
        val started = TipRules.Record(TipRules.State.STARTED, now - 1000)
        // Purchased but never acknowledged (e.g. app killed during the flow): acknowledge, then consume.
        val unacked = TipRules.decide(started, listOf(TipRules.Seen(true, false, false, "tok")), now)
        assertEquals(listOf(TipRules.Action.ACKNOWLEDGE_AND_CONSUME to "tok"), unacked)
        // Acknowledged but not consumed: consume.
        assertEquals(listOf(TipRules.Action.CONSUME to "t2"), TipRules.decide(started, listOf(TipRules.Seen(true, false, true, "t2")), now))
        // Still pending at the bank: keep the record and check again later.
        val pending = TipRules.Record(TipRules.State.PENDING, now - 5 * TipRules.ABANDON_MS)
        assertEquals(TipRules.Action.KEEP, TipRules.decide(pending, listOf(TipRules.Seen(false, true, false, "p")), now).single().first)
        assertEquals(TipRules.Action.KEEP, TipRules.decide(pending, emptyList(), now).single().first)
        // Started recently with nothing owned: keep; long abandoned: clear. Purchased + already consumed: clear.
        assertEquals(TipRules.Action.KEEP, TipRules.decide(started, emptyList(), now).single().first)
        assertEquals(TipRules.Action.CLEAR, TipRules.decide(started, emptyList(), now + TipRules.ABANDON_MS).single().first)
        assertEquals(TipRules.Action.CLEAR, TipRules.decide(TipRules.Record(TipRules.State.PURCHASED, now, "x"), emptyList(), now).single().first)
        // Record round trip.
        val r = TipRules.Record(TipRules.State.PURCHASED, 123L, "abc")
        assertEquals(r, TipRules.decode(TipRules.encode(r)))
        assertEquals(null, TipRules.decode("garbage"))
        assertTrue(A.simpleName == "Action")
    }

    @Test
    fun test42TankSizeIsPerProfile() {
        val root = Files.createTempDirectory("tank").toFile()
        ProfileStore.migrate(root, null, null, 1L)
        val other = ProfileStore.createProfile(root, "Car", 2L)
        ProfileStore.setTankEntered(root, "default", "35")
        assertEquals("35" to false, ProfileStore.tankGuess(root, "default"))
        assertEquals(null to false, ProfileStore.tankGuess(root, other))
        // Learned capacity replaces the guess for that profile only; tiny changes are not rewritten.
        assertTrue(ProfileStore.setTankGuess(root, "default", 32.03, true))
        assertEquals("32.0" to true, ProfileStore.tankGuess(root, "default"))
        assertFalse(ProfileStore.setTankGuess(root, "default", 32.04, true))
        assertTrue(ProfileStore.setTankGuess(root, other, 13.2, false))
        assertEquals("32.0" to true, ProfileStore.tankGuess(root, "default"))
        // Typing a value marks it as entered (not learned).
        ProfileStore.setTankEntered(root, "default", "34.5")
        assertEquals("34.5" to false, ProfileStore.tankGuess(root, "default"))
        assertEquals("13.2" to false, ProfileStore.tankGuess(root, other))
    }
}
