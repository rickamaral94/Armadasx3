package com.armadasx3.diagnostics

/** Small pure rules used when filling report.json. No Android dependencies, so they are unit tested. */
object Heuristics {
    /** By Build fields only. A heuristic: reports carry it so results can be filtered, not trusted blindly. */
    fun isOdin2Portal(manufacturer: String?, model: String?): Boolean =
        manufacturer.orEmpty().contains("ayn", ignoreCase = true) && model.orEmpty().contains("portal", ignoreCase = true)

    /** PS3 serials carry the region in their third letter (BLUS, BLES, BLJM, NPUB ...). */
    fun regionFromSerial(serial: String?): String? {
        val s = serial?.uppercase()?.takeIf { it.length >= 4 && it[0] in "BNS" } ?: return null
        return when (s[2]) {
            'U' -> "US"
            'E' -> "EU"
            'J' -> "JP"
            'A' -> "Asia"
            'K' -> "KR"
            'H' -> "HK"
            else -> null
        }
    }
}
