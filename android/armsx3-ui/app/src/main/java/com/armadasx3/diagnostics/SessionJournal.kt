package com.armadasx3.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-tolerant record of the current game session, written as it happens.
 *
 * The state file is rewritten atomically (temp file + rename) at the few points it changes, and
 * events are appended one line at a time, so a process that dies at any instant leaves a readable
 * journal behind. A session whose state is still in `current/` when the process next starts did
 * not end cleanly: [recoverInterrupted] moves it to `interrupted/`, where the export screen offers
 * it until the user exports or dismisses it.
 *
 * Layout under [dir]:
 *   current/      state.json + events.jsonl of the session running now
 *   last/         the most recent session that ended, clean or not
 *   interrupted/  the most recent session that did not end cleanly
 *
 * Bounded: events stop being written past [maxEventBytes]; the cut is recorded, not silent.
 * Nothing here ever throws to the caller; diagnostics must not be able to take a game down.
 */
class SessionJournal(
    private val dir: File,
    private val clock: Clock = Clock.System,
    private val maxEventBytes: Long = 256L * 1024L,
) {
    interface Clock {
        fun elapsedMs(): Long
        fun utcNow(): String

        object System : Clock {
            override fun elapsedMs(): Long = java.lang.System.nanoTime() / 1_000_000L
            override fun utcNow(): String = java.time.Instant.now().toString()
        }
    }

    @Serializable
    data class State(
        val id: String,
        @SerialName("started_at_utc") val startedAtUtc: String,
        @SerialName("ended_at_utc") val endedAtUtc: String? = null,
        @SerialName("end_state") val endState: String = END_RUNNING,
        @SerialName("duration_ms") val durationMs: Long? = null,
        @SerialName("process_uptime_at_start_s") val processUptimeAtStartS: Double? = null,
        val game: GameIdentity? = null,
        val driver: DriverInfo? = null,
        val settings: SettingsSnapshot? = null,
        @SerialName("events_dropped") val eventsDropped: Long = 0,
    )

    /** A finished session as read back from disk. */
    data class Record(val state: State, val events: List<SessionEvent>, val folder: File) {
        fun toSessionInfo(): SessionInfo = SessionInfo(
            id = state.id,
            startedAtUtc = state.startedAtUtc,
            endedAtUtc = state.endedAtUtc,
            endState = state.endState,
            durationMs = state.durationMs,
            processUptimeAtStartS = state.processUptimeAtStartS,
            events = events,
            eventsDropped = state.eventsDropped,
        )
    }

    private val currentDir get() = File(dir, CURRENT)
    private var startElapsed = 0L
    private var eventBytes = 0L
    private var dropped = 0L
    private var state: State? = null

    @Synchronized
    fun begin(
        id: String,
        processUptimeAtStartS: Double?,
        game: GameIdentity?,
        driver: DriverInfo?,
        settings: SettingsSnapshot?,
    ) = guard {
        // A session still open here means the previous one never reached end(): the VM was torn
        // down without returning to the library, or this process was restarted. Keep it.
        if (state != null) end(END_SUPERSEDED)
        else if (File(currentDir, STATE_FILE).exists()) recoverInterrupted()

        currentDir.deleteRecursively()
        currentDir.mkdirs()
        startElapsed = clock.elapsedMs()
        eventBytes = 0L
        dropped = 0L
        state = State(
            id = id,
            startedAtUtc = clock.utcNow(),
            processUptimeAtStartS = processUptimeAtStartS,
            game = game,
            driver = driver,
            settings = settings,
        ).also { writeState(currentDir, it) }
        event("session_start", game?.serial)
    }

    @Synchronized
    fun updateDriver(driver: DriverInfo) = guard {
        val s = state ?: return@guard
        state = s.copy(driver = driver).also { writeState(currentDir, it) }
        event("driver_pinned", listOfNotNull(driver.kind, driver.name, driver.version).joinToString(" / "))
    }

    /** The core identified the game after boot (direct boots start without a GameInfo). */
    @Synchronized
    fun updateGame(game: GameIdentity) = guard {
        val s = state ?: return@guard
        state = s.copy(game = game).also { writeState(currentDir, it) }
        event("game_identified", game.serial)
    }

    @Synchronized
    fun event(kind: String, detail: String? = null) = guard {
        if (state == null) return@guard
        val line = JSON.encodeToString(SessionEvent.serializer(), SessionEvent(clock.elapsedMs() - startElapsed, kind, detail)) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (eventBytes + bytes.size > maxEventBytes) {
            dropped++
            return@guard
        }
        FileOutputStream(File(currentDir, EVENTS_FILE), true).use { it.write(bytes); it.fd.sync() }
        eventBytes += bytes.size
    }

    /** The session returned to the library. [endState] is [END_CLEAN] unless the caller knows better. */
    @Synchronized
    fun end(endState: String = END_CLEAN) = guard {
        val s = state ?: return@guard
        event("session_end", endState)
        val finished = s.copy(
            endedAtUtc = clock.utcNow(),
            endState = endState,
            durationMs = clock.elapsedMs() - startElapsed,
            eventsDropped = dropped,
        )
        writeState(currentDir, finished)
        state = null
        promote(finished.endState != END_CLEAN && finished.endState != END_SUPERSEDED)
    }

    /**
     * Call once per process start, before any session begins. Returns true when a session was
     * left open by a previous process, which is what "the app crashed or was killed mid-game" looks
     * like from here.
     */
    @Synchronized
    fun recoverInterrupted(): Boolean = guardOr(false) {
        if (state != null) return@guardOr false
        val stateFile = File(currentDir, STATE_FILE)
        if (!stateFile.exists()) return@guardOr false
        val s = readState(stateFile) ?: State(id = "unknown", startedAtUtc = "unknown")
        // Duration is unknown: the monotonic clock of a dead process cannot be compared to ours.
        writeState(currentDir, s.copy(endState = END_INTERRUPTED))
        promote(interrupted = true)
        true
    }

    /** The session running now, as far as it has been written. */
    @Synchronized
    fun current(): Record? = guardOr(null) { if (state == null) null else read(currentDir) }

    @Synchronized
    fun last(): Record? = guardOr(null) { read(File(dir, LAST)) }

    @Synchronized
    fun interrupted(): Record? = guardOr(null) { read(File(dir, INTERRUPTED)) }

    @Synchronized
    fun dismissInterrupted() = guard { File(dir, INTERRUPTED).deleteRecursively() }

    /** current/ becomes last/ and, if it ended badly, a copy also becomes interrupted/. */
    private fun promote(interrupted: Boolean) {
        val last = File(dir, LAST)
        if (interrupted) {
            val target = File(dir, INTERRUPTED)
            target.deleteRecursively()
            currentDir.copyRecursively(target, overwrite = true)
        }
        last.deleteRecursively()
        if (!currentDir.renameTo(last)) {
            currentDir.copyRecursively(last, overwrite = true)
            currentDir.deleteRecursively()
        }
    }

    private fun read(folder: File): Record? {
        val s = readState(File(folder, STATE_FILE)) ?: return null
        val events = File(folder, EVENTS_FILE).takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            runCatching { JSON.decodeFromString(SessionEvent.serializer(), line) }.getOrNull()
        } ?: emptyList()
        return Record(s, events, folder)
    }

    private fun readState(file: File): State? =
        if (!file.exists()) null
        else runCatching { JSON.decodeFromString(State.serializer(), file.readText()) }.getOrNull()

    private fun writeState(folder: File, s: State) {
        folder.mkdirs()
        val tmp = File(folder, "$STATE_FILE.tmp")
        FileOutputStream(tmp).use {
            it.write(JSON.encodeToString(State.serializer(), s).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(File(folder, STATE_FILE))) {
            File(folder, STATE_FILE).delete()
            tmp.renameTo(File(folder, STATE_FILE))
        }
    }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (_: Throwable) { }
    }

    private inline fun <T> guardOr(fallback: T, block: () -> T): T =
        try { block() } catch (_: Throwable) { fallback }

    companion object {
        const val END_RUNNING = "running"
        const val END_CLEAN = "clean"
        const val END_INTERRUPTED = "interrupted"
        /** A new game started without the previous one reaching the library. */
        const val END_SUPERSEDED = "superseded"

        const val CURRENT = "current"
        const val LAST = "last"
        const val INTERRUPTED = "interrupted"
        const val STATE_FILE = "state.json"
        const val EVENTS_FILE = "events.jsonl"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
