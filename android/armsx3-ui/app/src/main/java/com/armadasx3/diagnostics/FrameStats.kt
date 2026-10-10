package com.armadasx3.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream

/**
 * Frame-time statistics over guest frame intervals (time between consecutive guest flips).
 *
 * Definitions, kept here so the report and the docs cannot drift apart:
 *  - percentiles are nearest-rank over the intervals;
 *  - `fps_avg` is frames divided by the summed intervals, not the mean of per-window FPS;
 *  - `fps_1pct_low` is 1000 / p99 frame time;
 *  - a *stutter* is an interval longer than twice the median;
 *  - a *hitch* is an interval of 100 ms or more (loading screens count: they are real pauses).
 */
@Serializable
data class FrameSummary(
    val frames: Long,
    @SerialName("total_ms") val totalMs: Double,
    @SerialName("fps_avg") val fpsAvg: Double,
    @SerialName("fps_1pct_low") val fps1PctLow: Double,
    @SerialName("frame_time_p50_ms") val p50Ms: Double,
    @SerialName("frame_time_p95_ms") val p95Ms: Double,
    @SerialName("frame_time_p99_ms") val p99Ms: Double,
    @SerialName("frame_time_max_ms") val maxMs: Double,
    @SerialName("stutters_over_2x_median") val stutters: Long,
    @SerialName("hitches_over_100ms") val hitches: Long,
)

object FrameStats {
    const val HITCH_US = 100_000L

    /** Summary of the first [count] values of [intervalsUs] (unsigned microseconds); null if empty. */
    fun summarize(intervalsUs: IntArray, count: Int = intervalsUs.size): FrameSummary? {
        if (count <= 0) return null
        val sorted = LongArray(count) { intervalsUs[it].toUInt().toLong() }
        sorted.sort()
        var total = 0L
        for (v in sorted) total += v
        if (total <= 0L) return null

        val median = rank(sorted, 0.50)
        val p99 = rank(sorted, 0.99)
        var stutters = 0L
        var hitches = 0L
        for (v in sorted) {
            if (v > 2 * median) stutters++
            if (v >= HITCH_US) hitches++
        }
        return FrameSummary(
            frames = count.toLong(),
            totalMs = total / 1000.0,
            fpsAvg = count * 1_000_000.0 / total,
            fps1PctLow = if (p99 > 0) 1_000_000.0 / p99 else 0.0,
            p50Ms = median / 1000.0,
            p95Ms = rank(sorted, 0.95) / 1000.0,
            p99Ms = p99 / 1000.0,
            maxMs = sorted.last() / 1000.0,
            stutters = stutters,
            hitches = hitches,
        )
    }

    /** Reads a frame_intervals.u32le stream (little-endian u32 microseconds). A trailing partial value is ignored. */
    fun readU32le(input: InputStream): IntArray {
        val bytes = input.readBytes()
        val n = bytes.size / 4
        return IntArray(n) { i ->
            (bytes[i * 4].toInt() and 0xff) or
                ((bytes[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((bytes[i * 4 + 3].toInt() and 0xff) shl 24)
        }
    }

    fun summarizeFile(file: File): FrameSummary? =
        if (!file.isFile) null else file.inputStream().use { summarize(readU32le(it)) }

    private fun rank(sorted: LongArray, p: Double): Long {
        val idx = (Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}

/**
 * One line of metrics.jsonl: what the sampler saw over one window (about a second).
 * Null means "not measurable on this device or with this core", never zero.
 */
@Serializable
data class MetricSample(
    /** Milliseconds since the session started, at the end of the window. */
    @SerialName("t_ms") val tMs: Long,
    @SerialName("window_ms") val windowMs: Long,
    val frames: Int? = null,
    val fps: Double? = null,
    @SerialName("frame_time_p50_ms") val p50Ms: Double? = null,
    @SerialName("frame_time_p99_ms") val p99Ms: Double? = null,
    @SerialName("frame_time_max_ms") val maxMs: Double? = null,
    /** CPU time of the thread group over the window, in percent of ONE core (can exceed 100). */
    @SerialName("ppu_cpu_pct") val ppuPct: Double? = null,
    @SerialName("spu_cpu_pct") val spuPct: Double? = null,
    @SerialName("rsx_cpu_pct") val rsxPct: Double? = null,
    @SerialName("ppu_threads") val ppuThreads: Int? = null,
    @SerialName("spu_threads") val spuThreads: Int? = null,
    /** Whole process, percent of ONE core, from /proc/self/stat. */
    @SerialName("process_cpu_pct") val processCpuPct: Double? = null,
    @SerialName("cpu_temp_c") val cpuTempC: Double? = null,
    @SerialName("gpu_temp_c") val gpuTempC: Double? = null,
    @SerialName("battery_temp_c") val batteryTempC: Double? = null,
    @SerialName("mem_avail_mb") val memAvailMb: Long? = null,
    @SerialName("core_frame_drops") val coreFrameDrops: Long? = null,
)

/** Averages and peaks of the per-window samples, for report.json. */
object SampleStats {
    data class Agg(val mean: Double, val max: Double, val n: Int)

    fun of(samples: List<MetricSample>, pick: (MetricSample) -> Double?): Agg? {
        val v = samples.mapNotNull(pick)
        if (v.isEmpty()) return null
        return Agg(v.average(), v.max(), v.size)
    }

    /**
     * CPU percent of one core from two cumulative readings. Null when the counter went backwards
     * (threads exited between samples, so the sum lost their time) or no time passed.
     */
    fun cpuPct(prevNs: Long, nowNs: Long, wallNs: Long): Double? {
        if (wallNs <= 0 || nowNs < prevNs) return null
        return (nowNs - prevNs) * 100.0 / wallNs
    }

    /** utime + stime (fields 14 and 15, clock ticks) from /proc/self/stat content. */
    fun procStatCpuTicks(stat: String): Long? {
        val afterComm = stat.substringAfterLast(')', "").trim()
        if (afterComm.isEmpty()) return null
        // afterComm starts at field 3 (state); utime is field 14 -> index 11 here.
        val f = afterComm.split(' ')
        if (f.size < 13) return null
        val utime = f[11].toLongOrNull() ?: return null
        val stime = f[12].toLongOrNull() ?: return null
        return utime + stime
    }
}
