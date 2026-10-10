package com.armadasx3.diagnostics

/**
 * JNI view of android/src/armadasx3_metrics.cpp, through the app's native glue (native-lib.cpp),
 * which resolves the core's symbols with dlsym. Every call degrades to "unavailable" when the glue
 * or the loaded core does not have the function, so an older core simply reports no metrics.
 */
object CoreMetrics {
    private external fun nativeAvailable(): Boolean
    private external fun nativeTakeFrameIntervals(out: IntArray): Int
    private external fun nativeFrameDrops(): Long
    private external fun nativeResetFrameIntervals()
    private external fun nativeThreadCpu(out: LongArray): Boolean

    data class ThreadCpu(
        val ppuNs: Long,
        val spuNs: Long,
        val rsxNs: Long,
        val ppuThreads: Int,
        val spuThreads: Int,
        /** Core's get_system_time() in microseconds at sampling; 0 when never sampled. */
        val sampledAtUs: Long,
    )

    val available: Boolean get() = runCatching { nativeAvailable() }.getOrDefault(false)

    /** Copies pending intervals into [out]; -1 when unavailable. */
    fun takeFrameIntervals(out: IntArray): Int = runCatching { nativeTakeFrameIntervals(out) }.getOrDefault(-1)

    fun frameDrops(): Long? = runCatching { nativeFrameDrops() }.getOrNull()?.takeIf { it >= 0 }

    fun resetFrameIntervals() {
        runCatching { nativeResetFrameIntervals() }
    }

    fun threadCpu(): ThreadCpu? = runCatching {
        val a = LongArray(6)
        if (!nativeThreadCpu(a) || a[5] == 0L) null
        else ThreadCpu(a[0], a[1], a[2], a[3].toInt(), a[4].toInt(), a[5])
    }.getOrNull()
}
