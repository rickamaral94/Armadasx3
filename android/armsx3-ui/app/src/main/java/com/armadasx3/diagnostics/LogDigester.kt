package com.armadasx3.diagnostics

/**
 * Reads an RPCS3-format log once, line by line, and keeps what a person triaging the bundle
 * reads first: how many lines of each level, and a bounded list of highlights.
 *
 * RPCS3 prefixes every line with a level marker ("·F ", "·E ", "·W " ...; see rpcs3/util/logs.cpp).
 * Counts are exact for the lines seen. Highlights are a capped sample, never the whole story, and
 * the digest says when it was capped.
 */
class LogDigester(
    private val fileLabel: String,
    private val maxHighlights: Int = 200,
    private val maxLineChars: Int = 400,
) {
    private var total = 0L
    private val counts = linkedMapOf<String, Long>()
    private val highlights = ArrayList<String>()
    private var truncated = false

    fun accept(line: String) {
        total++
        val level = levelOf(line)
        if (level != null) counts[level] = (counts[level] ?: 0L) + 1
        if (level == "fatal" || level == "error" || INTERESTING.any { line.contains(it, ignoreCase = true) }) {
            if (highlights.size < maxHighlights) {
                highlights += if (line.length > maxLineChars) line.substring(0, maxLineChars) + "…" else line
            } else {
                truncated = true
            }
        }
    }

    fun result(): LogDigest = LogDigest(
        file = fileLabel,
        totalLines = total,
        countByLevel = counts.toMap(),
        highlights = highlights.toList(),
        highlightsTruncated = truncated,
    )

    companion object {
        private val LEVELS = mapOf(
            'A' to "always", 'F' to "fatal", 'E' to "error", 'U' to "todo",
            'S' to "success", 'W' to "warning", '!' to "notice", 'T' to "trace",
        )

        /** Substrings that mark a line as worth surfacing whatever its level. */
        private val INTERESTING = listOf(
            "VK_ERROR_DEVICE_LOST", "device lost", "Found vulkan-compatible GPU", "Mobile GPU with limited testing",
            "driver pipeline cache", "Vulkan driver", "SIGSEGV", "SIGBUS", "SIGABRT", "Segfault",
            "Access violation", "Emulator stopped", "Thread terminated due to fatal error",
            "@@ANDROID_LAUNCH_GAME@@", "@@ANDROID_DATA_ROOT@@",
        )

        internal fun levelOf(line: String): String? {
            // Lines start with "·X " where · is U+00B7. Some writers prepend a timestamp, so look
            // a short way in rather than only at index 0.
            val idx = line.indexOf('·')
            if (idx < 0 || idx > 32 || idx + 2 >= line.length || line[idx + 2] != ' ') return null
            return LEVELS[line[idx + 1]]
        }
    }
}
