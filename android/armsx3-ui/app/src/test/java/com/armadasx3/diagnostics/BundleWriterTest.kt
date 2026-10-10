package com.armadasx3.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class BundleWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun report(r: BundleWriter.Result) = DiagnosticReport(
        generatedAtUtc = "2026-10-10T00:00:00Z",
        exportReason = "test",
        build = BuildInfo(),
        device = DeviceInfo(),
        logDigest = r.digests,
        files = r.files,
        unavailable = r.excluded,
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun writesRedactedLogsAndVersionedReport() {
        val log = tmp.newFile("ARMSX3.log").apply {
            writeText("·! 0:00:00.1 root=/storage/emulated/0/ARMSX3\n·E 0:00:01.0 sys_fs: failed\r\n")
        }
        val buf = ByteArrayOutputStream()
        val result = BundleWriter(PathRedactor()).write(
            buf,
            listOf(BundleEntry("logs/ARMSX3.log", "emulator_log", BundleEntry.Source.FromFile(log), digest = true)),
            ::report,
        )
        val files = unzip(buf.toByteArray())
        assertEquals(setOf("logs/ARMSX3.log", "report.json"), files.keys)
        assertTrue(files["logs/ARMSX3.log"]!!.contains("root=<internal-storage>/ARMSX3"))
        assertFalse(files["logs/ARMSX3.log"]!!.contains("\r"))

        val json = Json.parseToJsonElement(files["report.json"]!!).jsonObject
        assertEquals(DiagnosticReport.SCHEMA_NAME, json["schema"]!!.jsonPrimitive.content)
        assertEquals(DiagnosticReport.SCHEMA_VERSION, json["schema_version"]!!.jsonPrimitive.int)
        assertEquals(1L, result.digests.single().countByLevel["error"])
        assertEquals(2L, result.digests.single().totalLines)
        assertFalse(result.files.single().truncated)
    }

    @Test
    fun forbiddenFilesNeverReachTheZip() {
        val iso = tmp.newFile("game.iso").apply { writeText("not really") }
        val log = tmp.newFile("fine.log").apply { writeText("ok\n") }
        val buf = ByteArrayOutputStream()
        val result = BundleWriter(PathRedactor()).write(
            buf,
            listOf(
                BundleEntry("extra/game.iso", "x", BundleEntry.Source.FromFile(iso)),
                // An innocent zip name does not launder a forbidden source.
                BundleEntry("extra/notes.txt", "x", BundleEntry.Source.FromFile(iso)),
                BundleEntry("dev_hdd0/home/00000001/savedata/x.txt", "x", BundleEntry.Source.FromBytes(byteArrayOf(1))),
                BundleEntry("report.json", "x", BundleEntry.Source.FromBytes(byteArrayOf(1))),
                BundleEntry("logs/fine.log", "x", BundleEntry.Source.FromFile(log)),
            ),
            ::report,
        )
        val names = unzip(buf.toByteArray()).keys
        assertEquals(setOf("logs/fine.log", "report.json"), names)
        assertEquals(4, result.excluded.size)
    }

    @Test
    fun bigTextKeepsHeadAndTailWithMarker() {
        val lines = (0 until 2000).map { "line %05d %s".format(it, "x".repeat(40)) }
        val f = tmp.newFile("big.log").apply { writeText(lines.joinToString("\n") + "\n") }
        val buf = ByteArrayOutputStream()
        val result = BundleWriter(PathRedactor()).write(
            buf,
            listOf(BundleEntry("logs/big.log", "x", BundleEntry.Source.FromFile(f), maxStoredBytes = 16_000, digest = true)),
            ::report,
        )
        val text = unzip(buf.toByteArray())["logs/big.log"]!!
        assertTrue(text.startsWith("line 00000"))
        assertTrue(text.trimEnd().endsWith(lines.last()))
        assertTrue(text.contains("bytes omitted here"))
        val stored = result.files.single()
        assertTrue(stored.truncated)
        assertTrue("stored ${stored.storedBytes}", stored.storedBytes in 15_000..17_500)
        // The digest still saw every line, not only the stored ones.
        assertEquals(2000L, result.digests.single().totalLines)
    }

    @Test
    fun forbiddenReasonRules() {
        assertNull(BundleWriter.forbiddenReason("logs/ARMSX3.log"))
        assertNull(BundleWriter.forbiddenReason("config/config.yml"))
        assertTrue(BundleWriter.forbiddenReason("x/PS3UPDAT.PUP") != null)
        assertTrue(BundleWriter.forbiddenReason("a/dev_flash/sys/x.txt") != null)
        assertTrue(BundleWriter.forbiddenReason("NPUB00000.rap") != null)
    }

    @Test
    fun lineSplitterHandlesNoTrailingNewlineAndLongLines() {
        val input = ("a".repeat(5000) + "\nb\r\nc").toByteArray()
        val got = ArrayList<Pair<String, Int>>()
        BundleWriter.forEachLine(input.inputStream()) { b, len, consumed -> got += String(b, 0, len) to consumed }
        assertEquals(listOf(5000, 1, 1), got.map { it.first.length })
        assertEquals(input.size, got.sumOf { it.second })
    }
}
