package com.armadasx3.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicsTest {
    @Test
    fun regions() {
        assertEquals("US", Heuristics.regionFromSerial("BLUS30443"))
        assertEquals("EU", Heuristics.regionFromSerial("bles00932"))
        assertEquals("JP", Heuristics.regionFromSerial("BLJM60055"))
        assertEquals("US", Heuristics.regionFromSerial("NPUB30000"))
        assertNull(Heuristics.regionFromSerial("SLUS20062x".replace('S', 'X')))
        assertNull(Heuristics.regionFromSerial(null))
        assertNull(Heuristics.regionFromSerial("AB"))
    }

    @Test
    fun odinPortal() {
        assertTrue(Heuristics.isOdin2Portal("AYN", "Odin2 Portal"))
        assertFalse(Heuristics.isOdin2Portal("AYN", "Odin2"))
        assertFalse(Heuristics.isOdin2Portal("Retroid", "Pocket 5"))
        assertFalse(Heuristics.isOdin2Portal(null, null))
    }
}
