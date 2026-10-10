package com.armadasx3.diagnostics

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Turns a recorded session folder (frame_intervals.u32le + metrics.jsonl) into the `metrics` and
 * `unavailable` entries of report.json. Pure file reading, so it is unit tested.
 */
object SessionMetrics {
    private val json = Json { ignoreUnknownKeys = true }

    private const val FRAMES_SOURCE = "core: interval between guest flips (android/src/armadasx3_metrics.cpp)"
    private const val SAMPLE_SOURCE = "metrics.jsonl, about one sample per second"

    data class Result(val metrics: List<Metric>, val unavailable: List<Unavailable>)

    fun fromFolder(folder: File?): Result {
        if (folder == null) return Result(emptyList(), emptyList())
        val metrics = ArrayList<Metric>()
        val unavailable = ArrayList<Unavailable>()

        val frames = runCatching { FrameStats.summarizeFile(File(folder, SessionJournal.FRAMES_FILE)) }.getOrNull()
        if (frames == null) {
            unavailable += Unavailable(
                "fps_and_frame_time",
                "no guest frame intervals were recorded: the core in use does not export them, or the game presented no frames",
            )
        } else {
            val m = "whole session, exact over every recorded interval"
            metrics += Metric("session.frames", frames.frames.toDouble(), "frames", FRAMES_SOURCE, m)
            metrics += Metric("session.fps_avg", frames.fpsAvg, "fps", FRAMES_SOURCE, "$m; frames / summed intervals")
            metrics += Metric("session.fps_1pct_low", frames.fps1PctLow, "fps", FRAMES_SOURCE, "$m; 1000 / p99 frame time")
            metrics += Metric("session.frame_time_p50", frames.p50Ms, "ms", FRAMES_SOURCE, "$m; nearest-rank")
            metrics += Metric("session.frame_time_p95", frames.p95Ms, "ms", FRAMES_SOURCE, "$m; nearest-rank")
            metrics += Metric("session.frame_time_p99", frames.p99Ms, "ms", FRAMES_SOURCE, "$m; nearest-rank")
            metrics += Metric("session.frame_time_max", frames.maxMs, "ms", FRAMES_SOURCE, m)
            metrics += Metric("session.stutters", frames.stutters.toDouble(), "frames", FRAMES_SOURCE, "$m; intervals > 2x median")
            metrics += Metric("session.hitches", frames.hitches.toDouble(), "frames", FRAMES_SOURCE, "$m; intervals >= 100 ms, loading included")
        }

        val samples = File(folder, SessionJournal.METRICS_FILE).takeIf { it.isFile }?.useLines { lines ->
            lines.mapNotNull { runCatching { json.decodeFromString(MetricSample.serializer(), it) }.getOrNull() }.toList()
        }.orEmpty()

        fun agg(name: String, unit: String, what: String, pick: (MetricSample) -> Double?) {
            val a = SampleStats.of(samples, pick)
            if (a == null) {
                unavailable += Unavailable(name, "not measurable on this device or core ($what)")
            } else {
                metrics += Metric("$name.mean", a.mean, unit, SAMPLE_SOURCE, "mean of ${a.n} samples; $what")
                metrics += Metric("$name.max", a.max, unit, SAMPLE_SOURCE, "max of ${a.n} samples; $what")
            }
        }

        if (samples.isEmpty()) {
            unavailable += Unavailable("sampled_metrics", "no metric samples were recorded for this session")
        } else {
            agg("cpu.ppu", "% of one core", "CPU clock of all PPU threads") { it.ppuPct }
            agg("cpu.spu", "% of one core", "CPU clock of all SPU threads") { it.spuPct }
            agg("cpu.rsx", "% of one core", "CPU clock of the RSX thread") { it.rsxPct }
            agg("cpu.process", "% of one core", "utime+stime from /proc/self/stat") { it.processCpuPct }
            agg("temp.cpu", "°C", "thermal sysfs zone matched by name; vendor-specific") { it.cpuTempC }
            agg("temp.gpu", "°C", "thermal sysfs zone matched by name; vendor-specific") { it.gpuTempC }
            agg("temp.battery", "°C", "ACTION_BATTERY_CHANGED") { it.batteryTempC }
            SampleStats.of(samples) { it.memAvailMb?.toDouble() }?.let { a ->
                metrics += Metric("memory.available_min", samples.mapNotNull { it.memAvailMb }.min().toDouble(), "MB", SAMPLE_SOURCE, "lowest of ${a.n} samples (ActivityManager.MemoryInfo.availMem)")
            }
            samples.mapNotNull { it.coreFrameDrops }.maxOrNull()?.let { drops ->
                metrics += Metric("session.frame_intervals_lost", drops.toDouble(), "frames", "core ring buffer", "intervals overwritten before the app read them; 0 is expected")
            }
        }
        return Result(metrics, unavailable)
    }
}
