package com.armadasx3.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PathRedactorTest {
    private val r = PathRedactor()

    @Test
    fun primaryStorageKeepsFileName() {
        assertEquals(
            "Booting <internal-storage>/Games/PS3/Demo.iso",
            r.redact("Booting /storage/emulated/0/Games/PS3/Demo.iso"),
        )
        assertEquals("<internal-storage>/x.log", r.redact("/sdcard/x.log"))
        assertEquals("<internal-storage>/a", r.redact("/storage/emulated/10/a"))
    }

    @Test
    fun removableVolumeUuidIsHidden() {
        assertEquals("<external-storage>/PS3/game", r.redact("/storage/1A2B-3C4D/PS3/game"))
    }

    @Test
    fun appPrivatePathsAnyProfile() {
        assertEquals("<app-private>/files/x", r.redact("/data/user/0/com.armsx3/files/x"))
        assertEquals("<app-private>/cache", r.redact("/data/data/com.armsx3/cache"))
        assertEquals("<app-private>/f", r.redact("/data/user_de/11/com.armsx3/f"))
    }

    @Test
    fun safUrisAndDocumentIds() {
        val out = r.redact("open content://com.android.externalstorage.documents/tree/primary%3AGames/document/primary%3AGames%2Fa.iso ok")
        assertEquals("open content://<redacted> ok", out)
        assertEquals("doc <saf-document>", r.redact("doc primary:Games/PS3/a.iso"))
    }

    @Test
    fun emails() {
        assertFalse(r.redact("user someone@example.com logged").contains("someone"))
    }

    @Test
    fun knownRootsWinOverGenericRules() {
        val rr = PathRedactor(listOf("/storage/emulated/0/ARMSX3/" to "<data-root>"))
        assertEquals("<data-root>/cache/ARMSX3.log", rr.redact("/storage/emulated/0/ARMSX3/cache/ARMSX3.log"))
    }

    @Test
    fun unrelatedTextUntouched() {
        val line = "·E 0:00:12.345678 {PPU[0x1000000] main_thread} sys_fs: Failed to open file (ENOENT)"
        assertEquals(line, r.redact(line))
    }
}
