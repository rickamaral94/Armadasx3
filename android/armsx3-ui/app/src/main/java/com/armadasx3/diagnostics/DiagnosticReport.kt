package com.armadasx3.diagnostics

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * report.json -- the machine-readable heart of a diagnostic bundle.
 *
 * Versioned: [SCHEMA_VERSION] goes up whenever a field changes meaning or is removed. Adding an
 * optional field does not bump it. Tools that read old reports must keep working, so a field is
 * never repurposed; it is retired and a new name takes its place. docs/armadasx3/DIAGNOSTICO.md
 * documents every field.
 *
 * Every value that could not be measured is null and named in [unavailable] with the reason,
 * rather than being guessed or left out silently. A report is evidence; an invented number in it
 * is worse than a missing one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DiagnosticReport(
    @EncodeDefault @SerialName("schema") val schema: String = SCHEMA_NAME,
    @EncodeDefault @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("generated_at_utc") val generatedAtUtc: String,
    @SerialName("export_reason") val exportReason: String,
    val build: BuildInfo,
    val device: DeviceInfo,
    val session: SessionInfo? = null,
    val game: GameIdentity? = null,
    val driver: DriverInfo? = null,
    val settings: SettingsSnapshot? = null,
    @SerialName("user_notes") val userNotes: UserNotes = UserNotes(),
    @SerialName("log_digest") val logDigest: List<LogDigest> = emptyList(),
    val metrics: List<Metric> = emptyList(),
    val files: List<IncludedFile> = emptyList(),
    val unavailable: List<Unavailable> = emptyList(),
    val privacy: PrivacyInfo = PrivacyInfo(),
) {
    companion object {
        const val SCHEMA_NAME = "armadasx3.diagnostic-report"
        const val SCHEMA_VERSION = 1

        val json = Json {
            prettyPrint = true
            encodeDefaults = false
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
data class BuildInfo(
    @SerialName("app_id") val appId: String? = null,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("version_code") val versionCode: Long? = null,
    @SerialName("build_type") val buildType: String? = null,
    /** Commit of the core as stamped into git-version.h; null if the build did not record it. */
    @SerialName("core_git_version") val coreGitVersion: String? = null,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    @SerialName("soc_manufacturer") val socManufacturer: String? = null,
    @SerialName("soc_model") val socModel: String? = null,
    @SerialName("android_release") val androidRelease: String? = null,
    @SerialName("android_sdk") val androidSdk: Int? = null,
    @SerialName("supported_abis") val supportedAbis: List<String> = emptyList(),
    @SerialName("total_ram_bytes") val totalRamBytes: Long? = null,
    /** Matches the AYN Odin 2 Portal by Build fields; a heuristic, recorded so results can be filtered. */
    @SerialName("is_odin2_portal") val isOdin2Portal: Boolean? = null,
)

@Serializable
data class SessionInfo(
    val id: String,
    @SerialName("started_at_utc") val startedAtUtc: String,
    @SerialName("ended_at_utc") val endedAtUtc: String? = null,
    /** "clean" (returned to library), "interrupted" (process died with a game running) or "running". */
    @SerialName("end_state") val endState: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /**
     * Seconds between process start and session start. RPCS3 log timestamps count from the core's
     * start, which is close to process start, so this lines the session up with ARMSX3.log.
     */
    @SerialName("process_uptime_at_start_s") val processUptimeAtStartS: Double? = null,
    val events: List<SessionEvent> = emptyList(),
    @SerialName("events_dropped") val eventsDropped: Long = 0,
)

@Serializable
data class SessionEvent(
    /** Milliseconds since the session started (monotonic clock). */
    @SerialName("t_ms") val tMs: Long,
    val kind: String,
    val detail: String? = null,
)

@Serializable
data class GameIdentity(
    val title: String? = null,
    val serial: String? = null,
    val region: String? = null,
    val version: String? = null,
    val container: String? = null,
)

@Serializable
data class DriverInfo(
    /** "system" when no custom driver was pinned, "custom" for an adrenotools pack. */
    val kind: String,
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val vendor: String? = null,
    val author: String? = null,
    @SerialName("library_name") val libraryName: String? = null,
    @SerialName("library_sha256") val librarySha256: String? = null,
    @SerialName("env_overrides") val envOverrides: Map<String, String> = emptyMap(),
)

@Serializable
data class SettingsSnapshot(
    /** Effective app-level settings for the game (global merged with per-game). */
    val effective: JsonElement? = null,
    /** Only the per-game overrides. */
    @SerialName("per_game_overrides") val perGameOverrides: JsonElement? = null,
)

@Serializable
data class UserNotes(
    val scenario: String? = null,
    val observations: String? = null,
)

@Serializable
data class LogDigest(
    val file: String,
    @SerialName("total_lines") val totalLines: Long,
    @SerialName("count_by_level") val countByLevel: Map<String, Long>,
    /** Lines worth reading first: fatal/errors, Vulkan device loss, the GPU/driver the core chose. */
    val highlights: List<String>,
    @SerialName("highlights_truncated") val highlightsTruncated: Boolean,
)

/**
 * One measured value. [source] and [method] say where it came from so a reader can judge it;
 * a metric without both is not reported.
 */
@Serializable
data class Metric(
    val name: String,
    val value: Double,
    val unit: String,
    val source: String,
    val method: String,
    @SerialName("sampled_at_ms") val sampledAtMs: Long? = null,
)

@Serializable
data class IncludedFile(
    val path: String,
    val category: String,
    @SerialName("original_bytes") val originalBytes: Long,
    @SerialName("stored_bytes") val storedBytes: Long,
    val truncated: Boolean,
    val redacted: Boolean,
)

@Serializable
data class Unavailable(
    val item: String,
    val reason: String,
)

@Serializable
data class PrivacyInfo(
    @SerialName("paths_redacted") val pathsRedacted: Boolean = true,
    @SerialName("never_included") val neverIncluded: List<String> = listOf(
        "games and disc images", "firmware (PUP) and dev_flash", "BIOS", "keys, RAP/RIF/EDAT licences",
        "save data and save states", "account credentials", "personal files",
    ),
    @SerialName("sent_automatically") val sentAutomatically: Boolean = false,
)
