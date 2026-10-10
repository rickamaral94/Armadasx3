package com.armadasx3.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeClock : SessionJournal.Clock {
        var ms = 1_000L
        override fun elapsedMs() = ms
        override fun utcNow() = "2026-10-10T00:00:${(ms / 1000).toString().padStart(2, '0')}Z"
    }

    private val game = GameIdentity(title = "Test", serial = "BLUS00000")

    @Test
    fun cleanSessionEndsInLastNotInterrupted() {
        val clock = FakeClock()
        val j = SessionJournal(tmp.root, clock)
        j.begin("s1", 12.5, game, null, null)
        clock.ms += 5_000
        j.event("note", "hello")
        j.end()

        val last = assertNotNull(j.last())
        assertEquals("clean", last.state.endState)
        assertEquals(5_000L, last.state.durationMs)
        assertEquals(listOf("session_start", "note", "session_end"), last.events.map { it.kind })
        assertEquals(5_000L, last.events[1].tMs)
        assertNull(j.interrupted())
    }

    @Test
    fun sessionLeftOpenByDeadProcessIsRecoveredAsInterrupted() {
        SessionJournal(tmp.root, FakeClock()).begin("dead", null, game, null, null)
        // New process: a fresh journal object over the same folder.
        val j = SessionJournal(tmp.root, FakeClock())
        assertTrue(j.recoverInterrupted())
        assertFalse(j.recoverInterrupted())

        val r = assertNotNull(j.interrupted())
        assertEquals("dead", r.state.id)
        assertEquals("interrupted", r.state.endState)
        assertEquals("BLUS00000", r.state.game?.serial)
        assertEquals("session_start", r.events.single().kind)

        // A later clean session replaces last/ but keeps the interrupted one on offer.
        j.begin("next", null, game, null, null)
        j.end()
        assertEquals("next", j.last()?.state?.id)
        assertEquals("dead", j.interrupted()?.state?.id)

        j.dismissInterrupted()
        assertNull(j.interrupted())
    }

    @Test
    fun beginWithoutEndSupersedesPrevious() {
        val j = SessionJournal(tmp.root, FakeClock())
        j.begin("a", null, game, null, null)
        j.begin("b", null, game, null, null)
        j.end()
        assertEquals("b", j.last()?.state?.id)
        assertNull(j.interrupted())
    }

    @Test
    fun driverUpdateIsPersistedImmediately() {
        val j = SessionJournal(tmp.root, FakeClock())
        j.begin("s", null, game, null, null)
        j.updateDriver(DriverInfo(kind = "custom", name = "Turnip", version = "25.3"))
        // Simulate a crash right here.
        val k = SessionJournal(tmp.root, FakeClock())
        k.recoverInterrupted()
        assertEquals("Turnip", k.interrupted()?.state?.driver?.name)
    }

    @Test
    fun eventsAreBoundedAndTheCutIsCounted() {
        val j = SessionJournal(tmp.root, FakeClock(), maxEventBytes = 300)
        j.begin("s", null, game, null, null)
        repeat(50) { j.event("spam", "x".repeat(20)) }
        j.end()
        val last = assertNotNull(j.last())
        assertTrue(last.state.eventsDropped > 0)
        assertTrue(last.events.size < 50)
    }

    @Test
    fun eventsWithoutSessionAreIgnored() {
        val j = SessionJournal(tmp.root, FakeClock())
        j.event("orphan")
        j.end()
        assertNull(j.last())
    }

    private fun <T> assertNotNull(v: T?): T {
        org.junit.Assert.assertNotNull(v)
        return v!!
    }
}
