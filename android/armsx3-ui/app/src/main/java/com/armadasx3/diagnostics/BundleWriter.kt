package com.armadasx3.diagnostics

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One file the bundle could contain. The export screen lists these before anything is written,
 * so the user sees exactly what will leave the device and can drop the optional ones.
 */
data class BundleEntry(
    /** Path inside the zip. */
    val zipPath: String,
    val category: String,
    val source: Source,
    /** Text files are redacted line by line and may be trimmed; binary files are copied as is. */
    val isText: Boolean = true,
    /** Required entries cannot be deselected (report.json is generated, not listed here). */
    val optional: Boolean = true,
    val selectedByDefault: Boolean = true,
    /** Text above this is stored as head + tail with a marker in between. */
    val maxStoredBytes: Long = DEFAULT_MAX_TEXT_BYTES,
    /** Run [LogDigester] over it and put the digest in report.json. */
    val digest: Boolean = false,
) {
    sealed interface Source {
        val size: Long
        fun open(): InputStream

        data class FromFile(val file: File) : Source {
            override val size: Long get() = file.length()
            override fun open(): InputStream = file.inputStream()
        }

        class FromBytes(private val bytes: ByteArray) : Source {
            override val size: Long get() = bytes.size.toLong()
            override fun open(): InputStream = bytes.inputStream()
        }
    }

    companion object {
        const val DEFAULT_MAX_TEXT_BYTES = 16L * 1024L * 1024L
    }
}

/**
 * Writes the bundle. The only way a file reaches the zip is through here, so the privacy rules
 * live here too: whatever a caller passes, anything that looks like a game, firmware, key,
 * licence or save is refused and named in report.json as excluded.
 */
class BundleWriter(private val redactor: PathRedactor) {

    data class Result(val files: List<IncludedFile>, val digests: List<LogDigest>, val excluded: List<Unavailable>)

    fun write(
        out: OutputStream,
        entries: List<BundleEntry>,
        makeReport: (Result) -> DiagnosticReport,
    ): Result {
        val files = ArrayList<IncludedFile>()
        val digests = ArrayList<LogDigest>()
        val excluded = ArrayList<Unavailable>()
        val usedPaths = HashSet<String>()

        ZipOutputStream(out).use { zip ->
            for (entry in entries) {
                val path = entry.zipPath.trimStart('/')
                // The zip path is checked in full; of the source only the file name, since the
                // folder above it is wherever the user chose to keep their data.
                val forbidden = forbiddenReason(path)
                    ?: (entry.source as? BundleEntry.Source.FromFile)?.let { forbiddenReason(it.file.name) }
                if (forbidden != null) {
                    excluded += Unavailable(path, "excluded: $forbidden")
                    continue
                }
                if (path == REPORT_NAME || !usedPaths.add(path)) {
                    excluded += Unavailable(path, "excluded: duplicate name in bundle")
                    continue
                }
                val written = runCatching {
                    zip.putNextEntry(ZipEntry(path))
                    val r = if (entry.isText) writeText(zip, entry, path) else writeBinary(zip, entry, path)
                    zip.closeEntry()
                    r
                }
                written.onSuccess { (file, digest) ->
                    files += file
                    if (digest != null) digests += digest
                }.onFailure {
                    excluded += Unavailable(path, "could not be read: ${it.javaClass.simpleName}")
                    runCatching { zip.closeEntry() }
                }
            }

            val result = Result(files.toList(), digests.toList(), excluded.toList())
            zip.putNextEntry(ZipEntry(REPORT_NAME))
            zip.write(DiagnosticReport.json.encodeToString(DiagnosticReport.serializer(), makeReport(result)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            return result
        }
    }

    private fun writeBinary(zip: OutputStream, entry: BundleEntry, path: String): Pair<IncludedFile, LogDigest?> {
        val n = entry.source.open().use { it.copyTo(zip) }
        return IncludedFile(path, entry.category, n, n, truncated = false, redacted = false) to null
    }

    /**
     * One pass over the whole source: every line feeds the digest, but only lines that start in
     * the first [headShare] of the budget or in the final stretch are stored. Lines are split on
     * raw bytes so offsets are exact and a multi-byte character is never cut in half.
     */
    private fun writeText(zip: OutputStream, entry: BundleEntry, path: String): Pair<IncludedFile, LogDigest?> {
        val size = entry.source.size
        val budget = entry.maxStoredBytes
        val trimming = size > budget
        val headEnd = if (trimming) budget / 8 else Long.MAX_VALUE
        val tailStart = if (trimming) size - (budget - headEnd) else Long.MAX_VALUE
        val digester = if (entry.digest) LogDigester(path) else null

        var stored = 0L
        var markerWritten = false
        var offset = 0L
        entry.source.open().use { input ->
            forEachLine(input) { bytes, len, consumed ->
                val line = String(bytes, 0, len, Charsets.UTF_8)
                digester?.accept(line)
                val keep = !trimming || offset < headEnd || offset >= tailStart
                if (keep) {
                    val outBytes = (redactor.redact(line) + "\n").toByteArray(Charsets.UTF_8)
                    zip.write(outBytes)
                    stored += outBytes.size
                } else if (!markerWritten) {
                    val marker = "\n[armadasx3: ${tailStart - headEnd} bytes omitted here; the file was $size bytes]\n\n"
                        .toByteArray(Charsets.UTF_8)
                    zip.write(marker)
                    stored += marker.size
                    markerWritten = true
                }
                offset += consumed
            }
        }
        return IncludedFile(path, entry.category, size, stored, truncated = trimming, redacted = true) to digester?.result()
    }

    companion object {
        const val REPORT_NAME = "report.json"

        private val FORBIDDEN_EXTENSIONS = setOf(
            "iso", "bin", "cue", "chd", "pkg", "pup", "rap", "rif", "edat", "sdat", "self", "sprx",
            "elf", "key", "bak", "sav", "savestat", "pfd", "sfo", "tropusr", "dat",
        )
        private val FORBIDDEN_SEGMENTS = listOf(
            "dev_flash", "dev_hdd0/home", "dev_hdd0/game", "dev_hdd1", "savedata", "savestates",
            "exdata", "licenses", "firmware", "bios",
        )

        /** Why [path] may not go into a bundle, or null if it may. */
        fun forbiddenReason(path: String): String? {
            val p = path.replace('\\', '/').lowercase()
            val ext = p.substringAfterLast('/').substringAfterLast('.', "")
            if (ext in FORBIDDEN_EXTENSIONS) return "file type .$ext is never exported"
            FORBIDDEN_SEGMENTS.firstOrNull { p.contains(it) }?.let { return "files under '$it' are never exported" }
            return null
        }

        /**
         * Calls [onLine] with each line's bytes, its length without the line ending (a trailing
         * '\r' is dropped) and the number of bytes it consumed from [input].
         */
        internal inline fun forEachLine(input: InputStream, onLine: (ByteArray, Int, Int) -> Unit) {
            val buf = ByteArray(64 * 1024)
            var line = ByteArray(1024)
            var len = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i]
                    if (b == '\n'.code.toByte()) {
                        val l = if (len > 0 && line[len - 1] == '\r'.code.toByte()) len - 1 else len
                        onLine(line, l, len + 1)
                        len = 0
                    } else {
                        if (len == line.size) line = line.copyOf(line.size * 2)
                        line[len++] = b
                    }
                }
            }
            if (len > 0) onLine(line, len, len)
        }
    }
}
