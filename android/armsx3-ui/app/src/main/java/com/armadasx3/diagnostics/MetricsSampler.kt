package com.armadasx3.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.armsx2.Thermals
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Once a second while a game runs: drains the core's guest frame intervals into the journal and
 * writes one [MetricSample] line. Runs on its own thread; per tick it does a JNI copy, a few file
 * reads and two small appends, nothing per frame.
 *
 * Sources: frame intervals and PPU/SPU/RSX thread CPU from the core (CoreMetrics); process CPU from
 * /proc/self/stat; temperatures from the app's existing Thermals reader (thermal sysfs + battery
 * broadcast, which work without root on many devices but not all); free memory from
 * ActivityManager. Anything unreadable is null in the sample.
 */
class MetricsSampler(private val context: Context, private val journal: SessionJournal) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "armadasx3-metrics").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private val buf = IntArray(4096)
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private var startMs = 0L
    private var lastTickMs = 0L
    private var lastCpu: CoreMetrics.ThreadCpu? = null
    private var lastProcTicks: Long? = null
    private val clkTck: Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

    @Synchronized
    fun start() {
        stop()
        CoreMetrics.resetFrameIntervals()
        startMs = SystemClock.elapsedRealtime()
        lastTickMs = startMs
        lastCpu = null
        lastProcTicks = readProcTicks()
        task = exec.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * Stops sampling and flushes what the core queued since the last tick. Waits for that last
     * tick, so call it off the main thread.
     */
    @Synchronized
    fun stop() {
        val t = task ?: return
        task = null
        t.cancel(false)
        runCatching { exec.submit(Runnable { runCatching { tick() } }).get(2, TimeUnit.SECONDS) }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val windowMs = now - lastTickMs
        lastTickMs = now

        // Frames: drain the ring completely; keep the window's own values for its line.
        var windowFrames = IntArray(0)
        while (true) {
            val n = CoreMetrics.takeFrameIntervals(buf)
            if (n <= 0) break
            journal.appendFrames(buf, n)
            windowFrames += buf.copyOf(n)
            if (n < buf.size) break
        }
        val frames = FrameStats.summarize(windowFrames)

        // Thread CPU, against the core's own sampling clock.
        val cpu = CoreMetrics.threadCpu()
        val prev = lastCpu
        var ppu: Double? = null
        var spu: Double? = null
        var rsx: Double? = null
        if (cpu != null && prev != null && cpu.sampledAtUs > prev.sampledAtUs) {
            val wallNs = (cpu.sampledAtUs - prev.sampledAtUs) * 1000
            ppu = SampleStats.cpuPct(prev.ppuNs, cpu.ppuNs, wallNs)
            spu = SampleStats.cpuPct(prev.spuNs, cpu.spuNs, wallNs)
            rsx = SampleStats.cpuPct(prev.rsxNs, cpu.rsxNs, wallNs)
        }
        if (cpu != null && (prev == null || cpu.sampledAtUs != prev.sampledAtUs)) lastCpu = cpu

        val procTicks = readProcTicks()
        val procPct = if (procTicks != null && lastProcTicks != null && windowMs > 0) {
            (procTicks - lastProcTicks!!) * 1000.0 / clkTck * 100.0 / windowMs
        } else null
        lastProcTicks = procTicks

        runCatching { Thermals.poll(context, 1000) }
        fun temp(v: Float): Double? = if (v == Thermals.NONE) null else v.toDouble()

        val mem = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / (1024 * 1024)
        }.getOrNull()

        val sample = MetricSample(
            tMs = now - startMs,
            windowMs = windowMs,
            frames = frames?.frames?.toInt() ?: if (CoreMetrics.available) 0 else null,
            fps = frames?.fpsAvg,
            p50Ms = frames?.p50Ms,
            p99Ms = frames?.p99Ms,
            maxMs = frames?.maxMs,
            ppuPct = ppu,
            spuPct = spu,
            rsxPct = rsx,
            ppuThreads = cpu?.ppuThreads,
            spuThreads = cpu?.spuThreads,
            processCpuPct = procPct,
            cpuTempC = temp(Thermals.cpu),
            gpuTempC = temp(Thermals.gpu),
            batteryTempC = temp(Thermals.battery),
            memAvailMb = mem,
            coreFrameDrops = CoreMetrics.frameDrops(),
        )
        journal.appendMetric(json.encodeToString(MetricSample.serializer(), sample))
    }

    private fun readProcTicks(): Long? =
        runCatching { SampleStats.procStatCpuTicks(File("/proc/self/stat").readText()) }.getOrNull()
}
