package com.armadasx3.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FrameStatsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun steadySixtyWithOneHitch() {
        // 99 frames at 16.667 ms and one 200 ms hitch.
        val v = IntArray(100) { if (it == 50) 200_000 else 16_667 }
        val s = FrameStats.summarize(v)!!
        assertEquals(100L, s.frames)
        assertEquals(16.667, s.p50Ms, 1e-9)
        assertEquals(16.667, s.p99Ms, 1e-9) // nearest-rank: the 99th of 100 sorted values
        assertEquals(200.0, s.maxMs, 1e-9)
        assertEquals(1L, s.stutters)
        assertEquals(1L, s.hitches)
        assertEquals(100 * 1_000_000.0 / (99 * 16_667 + 200_000), s.fpsAvg, 1e-9)
    }

    @Test
    fun percentilesNearestRank() {
        val v = IntArray(20) { (it + 1) * 1000 } // 1..20 ms
        val s = FrameStats.summarize(v)!!
        assertEquals(10.0, s.p50Ms, 1e-9)
        assertEquals(19.0, s.p95Ms, 1e-9)
        assertEquals(20.0, s.p99Ms, 1e-9)
        assertEquals(1000.0 / 20.0, s.fps1PctLow, 1e-9)
    }

    @Test
    fun unsignedIntervalsAboveIntMax() {
        // 0xF0000000 us is about 67 minutes: a u32 the core can produce for a very long pause.
        val s = FrameStats.summarize(intArrayOf(0xF0000000.toInt()))!!
        assertEquals(0xF0000000L / 1000.0, s.maxMs, 1e-6)
    }

    @Test
    fun emptyIsNull() {
        assertNull(FrameStats.summarize(IntArray(0)))
        assertNull(FrameStats.summarize(intArrayOf(1, 2), count = 0))
    }

    @Test
    fun journalWritesU32leThatReadsBack() {
        val j = SessionJournal(tmp.root, maxFrameBytes = 4 * 5)
        j.begin("s", null, null, null, null)
        j.appendFrames(intArrayOf(1, 256, 65_536, 16_777_216, -1, 7, 8), 7)
        j.end()
        val f = File(tmp.root, "last/${SessionJournal.FRAMES_FILE}")
        val back = f.inputStream().use { FrameStats.readU32le(it) }
        assertEquals(listOf(1, 256, 65_536, 16_777_216, -1), back.toList())
        assertEquals(2L, j.last()!!.state.framesNotStored)
    }
}
