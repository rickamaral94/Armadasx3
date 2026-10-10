package com.armadasx3.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionMetricsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun summarizesFramesAndSamples() {
        val j = SessionJournal(tmp.root)
        j.begin("s", null, null, null, null)
        j.appendFrames(IntArray(60) { 33_333 }, 60)
        val json = Json { explicitNulls = false }
        j.appendMetric(json.encodeToString(MetricSample.serializer(), MetricSample(tMs = 1000, windowMs = 1000, ppuPct = 50.0, spuPct = 120.0, cpuTempC = 40.0, memAvailMb = 3000)))
        j.appendMetric(json.encodeToString(MetricSample.serializer(), MetricSample(tMs = 2000, windowMs = 1000, ppuPct = 70.0, spuPct = 140.0, cpuTempC = 44.0, memAvailMb = 2500)))
        j.appendMetric("not json")
        j.end()

        val r = SessionMetrics.fromFolder(j.last()!!.folder)
        val m = r.metrics.associateBy { it.name }
        assertEquals(60.0, m.getValue("session.frames").value, 0.0)
        assertEquals(30.0, m.getValue("session.fps_avg").value, 0.01)
        assertEquals(60.0, m.getValue("cpu.ppu.mean").value, 1e-9)
        assertEquals(140.0, m.getValue("cpu.spu.max").value, 1e-9)
        assertEquals(44.0, m.getValue("temp.cpu.max").value, 1e-9)
        assertEquals(2500.0, m.getValue("memory.available_min").value, 0.0)
        // Every metric names where it came from.
        assertTrue(r.metrics.all { it.source.isNotBlank() && it.method.isNotBlank() })
        // Not measured -> reported as unavailable, not as zero.
        assertTrue(r.unavailable.any { it.item == "cpu.rsx" })
        assertTrue(r.unavailable.any { it.item == "temp.gpu" })
        assertNull(m["cpu.rsx.mean"])
    }

    @Test
    fun emptySessionSaysWhy() {
        val j = SessionJournal(tmp.root)
        j.begin("s", null, null, null, null)
        j.end()
        val r = SessionMetrics.fromFolder(j.last()!!.folder)
        assertTrue(r.metrics.isEmpty())
        assertEquals(setOf("fps_and_frame_time", "sampled_metrics"), r.unavailable.map { it.item }.toSet())
    }

    @Test
    fun cpuPctAndProcStat() {
        assertEquals(50.0, SampleStats.cpuPct(1_000, 501_000, 1_000_000)!!, 1e-9)
        assertNull(SampleStats.cpuPct(2_000, 1_000, 1_000_000)) // thread exited: counter went back
        assertNull(SampleStats.cpuPct(0, 1, 0))
        // pid (comm with spaces) state ... utime=1234 stime=56 at fields 14/15.
        val stat = "4321 (armsx3 core) S 1 2 3 4 5 6 7 8 9 10 1234 56 0 0 20 0 30 0 100 0"
        assertEquals(1290L, SampleStats.procStatCpuTicks(stat))
        assertNull(SampleStats.procStatCpuTicks("garbage"))
    }
}
