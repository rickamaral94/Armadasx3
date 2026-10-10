package com.armadasx3.diagnostics

/**
 * Removes the personal part of paths and identifiers from text that goes into a bundle.
 *
 * Kept: file names and the structure inside the app's own folders, because "which file failed"
 * is usually the point of the log line. Removed: the storage volume, the user profile number,
 * folder names above the app data, content:// authorities and document ids, and e-mail addresses.
 *
 * This is best effort. It is applied to every text file in the bundle and the export screen says
 * so; it is not a promise that nothing personal can remain in a free-form log.
 */
class PathRedactor(
    /** Absolute data roots the app knows about (e.g. the chosen data folder). Longest first wins. */
    knownRoots: List<Pair<String, String>> = emptyList(),
) {
    private val roots: List<Pair<String, String>> = knownRoots
        .filter { it.first.length > 1 }
        .map { (path, label) -> path.trimEnd('/') to label }
        .sortedByDescending { it.first.length }

    fun redact(line: String): String {
        var out = line
        for ((path, label) in roots) {
            if (out.contains(path)) out = out.replace(path, label)
        }
        for ((regex, replacement) in RULES) {
            out = regex.replace(out, replacement)
        }
        return out
    }

    companion object {
        private val RULES: List<Pair<Regex, String>> = listOf(
            // App-private storage, any user profile: /data/user/0/<pkg>, /data/data/<pkg>.
            Regex("""/data/(?:user(?:_de)?/\d+|data)/[A-Za-z0-9._]+""") to "<app-private>",
            // Primary shared storage, any profile, with or without the /storage prefix.
            Regex("""(?:/storage/emulated/\d+|/sdcard|/mnt/user/\d+/emulated/\d+)(?=/|$|\s|["'),;:])""") to "<internal-storage>",
            // Removable volumes are named by their filesystem UUID.
            Regex("""/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(?=/|$|\s|["'),;:])""") to "<external-storage>",
            // SAF: content://authority/tree/<doc id>/document/<doc id>. The ids embed the path.
            Regex("""content://[^\s"']+""") to "content://<redacted>",
            // SAF document ids that escape into logs on their own ("primary:Games/x.iso").
            Regex("""\b(?:primary|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}):[^\s"']*""") to "<saf-document>",
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        )
    }
}
