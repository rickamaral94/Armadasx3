package com.armadasx3.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDigesterTest {
    @Test
    fun levelsAreParsedFromRpcs3Markers() {
        assertEquals("error", LogDigester.levelOf("·E 0:00:01.000 {rsx::thread} RSX: boom"))
        assertEquals("fatal", LogDigester.levelOf("·F 0:00:01.000 x"))
        assertEquals("notice", LogDigester.levelOf("·! 0:00:01.000 x"))
        assertNull(LogDigester.levelOf("plain text line"))
        assertNull(LogDigester.levelOf("·"))
    }

    @Test
    fun countsAndHighlights() {
        val d = LogDigester("ARMSX3.log", maxHighlights = 2)
        listOf(
            "·! 0:00:00.1 boot",
            "·E 0:00:01.0 first error",
            "·W 0:00:01.1 warn",
            "·E 0:00:02.0 second error",
            "·! 0:00:03.0 vk: VK_ERROR_DEVICE_LOST while presenting",
        ).forEach(d::accept)
        val r = d.result()
        assertEquals(5L, r.totalLines)
        assertEquals(2L, r.countByLevel["error"])
        assertEquals(2L, r.countByLevel["notice"])
        assertEquals(listOf("·E 0:00:01.0 first error", "·E 0:00:02.0 second error"), r.highlights)
        assertTrue(r.highlightsTruncated)
    }

    @Test
    fun longLinesAreClipped() {
        val d = LogDigester("x", maxLineChars = 10)
        d.accept("·E " + "a".repeat(100))
        assertEquals(11, d.result().highlights.single().length)
    }
}
