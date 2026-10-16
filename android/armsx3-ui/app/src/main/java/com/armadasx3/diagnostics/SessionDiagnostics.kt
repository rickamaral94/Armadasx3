package com.armadasx3.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import com.armsx2.CustomDriver
import com.armsx2.GameInfo
import com.armsx2.config.ConfigStore
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Android side of session diagnostics: feeds [SessionJournal] from the app's lifecycle and builds
 * the bundle "Export session diagnostics" writes.
 *
 * Hooks (each one line, each wrapped so a failure here cannot reach the caller):
 *  - [init] from Application.onCreate: recovers a session a dead process left open.
 *  - [onGameStarted] / [onDriverPinned] / [onReturnedToLibrary] from MainActivityRuntime.
 *
 * Journal writes go through one background thread: they fsync, and the launch path runs on the
 * main thread. What is collected is limited to public, unprivileged APIs; nothing here needs root,
 * ADB or READ_LOGS, and whatever that leaves out is listed in report.json as unavailable.
 */
object SessionDiagnostics {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "armadasx3-diag").apply { isDaemon = true } }

    @Volatile private var journal: SessionJournal? = null
    @Volatile private var app: Application? = null
    @Volatile private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var sessionActive = false
    @Volatile private var sampler: MetricsSampler? = null

    fun diagnosticsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")

    fun init(application: Application) {
        runCatching {
            app = application
            val j = SessionJournal(diagnosticsDir(application))
            journal = j
            sampler = MetricsSampler(application, j)
            io.execute { j.recoverInterrupted() }
            application.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (sessionActive) event("trim_memory", trimLevelName(level))
                }
                override fun onConfigurationChanged(newConfig: Configuration) {}
                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    if (sessionActive) event("low_memory")
                }
            })
        }
    }

    fun event(kind: String, detail: String? = null) {
        val j = journal ?: return
        runCatching { io.execute { j.event(kind, detail) } }
    }

    fun onGameStarted(info: GameInfo?) {
        runCatching {
            val j = journal ?: return
            val ctx = app ?: return
            val uptimeS = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000.0
            val game = gameIdentity(info)
            val serial = game.serial
            val settings = runCatching {
                SettingsSnapshot(
                    effective = Json.parseToJsonElement(ConfigStore.resolveForGame(serial).toJson().toString()),
                    perGameOverrides = serial?.let { s ->
                        ConfigStore.loadOverrides(s)?.let { Json.parseToJsonElement(it.toString()) }
                    },
                )
            }.getOrNull()
            sessionActive = true
            io.execute {
                j.begin(UUID.randomUUID().toString(), uptimeS, game, null, settings)
                j.event("memory_at_start", memoryLine(ctx))
                thermalStatus(ctx)?.let { j.event("thermal_status", it) }
                sampler?.start()
            }
            watchThermal(ctx)
        }
    }

    fun onGameIdentified(info: GameInfo?) {
        runCatching {
            val j = journal ?: return
            if (info == null || !sessionActive) return
            val game = gameIdentity(info)
            io.execute { j.updateGame(game) }
        }
    }

    private fun gameIdentity(info: GameInfo?): GameIdentity {
        val serial = info?.serial?.takeIf { it.isNotBlank() }
        return GameIdentity(
            title = info?.title,
            serial = serial,
            region = Heuristics.regionFromSerial(serial),
            version = null,
            container = info?.extension?.takeIf { it.isNotBlank() },
        )
    }

    fun onDriverPinned(picked: CustomDriver.InstalledDriver?) {
        runCatching {
            val j = journal ?: return
            val env = runCatching {
                CustomDriver.driverEnv(CustomDriver.driverEnvText(), CustomDriver.ubwcFlagHint()).toMap()
            }.getOrDefault(emptyMap())
            io.execute {
                val info = if (picked == null) {
                    DriverInfo(kind = "system", envOverrides = env)
                } else {
                    DriverInfo(
                        kind = "custom",
                        id = picked.id,
                        name = picked.name,
                        version = picked.version.ifBlank { null },
                        vendor = picked.vendor.ifBlank { null },
                        author = picked.author.ifBlank { null },
                        libraryName = picked.libraryName,
                        librarySha256 = runCatching { sha256(picked.driverFile) }.getOrNull(),
                        envOverrides = env,
                    )
                }
                j.updateDriver(info)
            }
        }
    }

    fun onReturnedToLibrary() {
        runCatching {
            val j = journal ?: return
            if (!sessionActive) return
            sessionActive = false
            unwatchThermal()
            val ctx = app
            io.execute {
                sampler?.stop()
                if (ctx != null) j.event("memory_at_end", memoryLine(ctx))
                j.end()
            }
        }
    }

    // ---- Export -------------------------------------------------------------------------------

    enum class Target { CURRENT, INTERRUPTED, LAST, NONE }

    data class Plan(val target: Target, val record: SessionJournal.Record?, val entries: List<BundleEntry>)

    /** Which sessions can be exported right now, most relevant first. */
    fun availableTargets(): List<Pair<Target, SessionJournal.Record>> {
        val j = journal ?: return emptyList()
        // Let queued writes land before reading what they wrote.
        runCatching { io.submit(Runnable {}).get() }
        return listOfNotNull(
            j.current()?.let { Target.CURRENT to it },
            j.interrupted()?.let { Target.INTERRUPTED to it },
            j.last()?.let { Target.LAST to it },
        )
    }

    fun dismissInterrupted() {
        val j = journal ?: return
        runCatching { io.submit(Runnable { j.dismissInterrupted() }).get() }
    }

    fun plan(context: Context, target: Target, record: SessionJournal.Record?): Plan {
        val root = runCatching { File(MainActivityRuntime.assetCopyRoot(context)) }.getOrNull()
        val appLogs = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        val serial = record?.state?.game?.serial
        val entries = ArrayList<BundleEntry>()

        fun file(
            zipPath: String, category: String, f: File?, selected: Boolean = true, optional: Boolean = true,
            digest: Boolean = false, isText: Boolean = true,
        ) {
            if (f != null && f.isFile && f.length() > 0) {
                entries += BundleEntry(
                    zipPath, category, BundleEntry.Source.FromFile(f), isText = isText,
                    optional = optional, selectedByDefault = selected, digest = digest,
                )
            }
        }

        record?.folder?.let { folder ->
            file("session/state.json", "session_journal", File(folder, SessionJournal.STATE_FILE), optional = false)
            file("session/events.jsonl", "session_journal", File(folder, SessionJournal.EVENTS_FILE), optional = false)
            file("session/metrics.jsonl", "session_metrics", File(folder, SessionJournal.METRICS_FILE))
            file("session/frame_intervals.u32le", "session_metrics", File(folder, SessionJournal.FRAMES_FILE), isText = false)
        }
        if (root != null) {
            val cache = File(root, "cache")
            // After a crash the relaunch rotates the session's log to .old, so both go by default.
            file("logs/ARMSX3.log", "emulator_log", File(cache, "ARMSX3.log"), digest = true)
            file("logs/ARMSX3.old.log", "emulator_log", File(cache, "ARMSX3.old.log"), digest = true)
            file("logs/ARMSX3.old2.log", "emulator_log", File(cache, "ARMSX3.old2.log"), selected = false, digest = true)
            file("logs/ARMSX3.old3.log", "emulator_log", File(cache, "ARMSX3.old3.log"), selected = false, digest = true)
            file("config/config.yml", "emulator_config", File(root, "config/config.yml"))
            if (serial != null) {
                file("config/custom_configs/config_$serial.yml", "game_config", File(root, "config/custom_configs/config_$serial.yml"))
            }
            file("config/driver_env.txt", "driver_config", File(root, "driver_env.txt").takeIf { it.isFile } ?: File(root, "config/driver_env.txt"))
            // Only a PGO-instrumented core (-DARMSX3_PGO=generate) writes these: per-function
            // execution counters, flushed when the app goes to the background. No paths, no game
            // data -- they feed the next optimised build. Newest first, a few at most.
            File(cache, "pgo").listFiles { f -> f.name.endsWith(".profraw") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(4)
                ?.forEach { file("pgo/${it.name}", "pgo_profile", it, isText = false) }
        }
        file("logs/session.log", "app_log", File(appLogs, "session.log"))
        appLogs.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.forEach { file("logs/${it.name}", "app_crash", it) }

        return Plan(target, record, entries)
    }

    fun suggestedName(plan: Plan): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val serial = plan.record?.state?.game?.serial?.let { "-" + it.filter(Char::isLetterOrDigit) } ?: ""
        return "armadasx3-diagnostico-$stamp$serial.zip"
    }

    /** Outlives the settings screen, so an export the user walked away from still finishes. */
    val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Builds the bundle in the app's cache and only then copies it to [uri]. Written straight to
     * the destination, a 28 MB log took long enough to redact that the half-written zip was
     * already in the user's folder, and two bundles were shared cut off mid-log (no report.json).
     * On failure the empty destination document is removed rather than left looking like a bundle.
     */
    fun exportTo(context: Context, uri: Uri, plan: Plan, selected: Set<String>, notes: UserNotes): BundleWriter.Result {
        val tmp = File.createTempFile("diag-", ".zip", context.cacheDir)
        try {
            val result = tmp.outputStream().buffered().use { export(context, it, plan, selected, notes) }
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("could not open destination")
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            return result
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw t
        } finally {
            tmp.delete()
        }
    }

    fun export(context: Context, out: OutputStream, plan: Plan, selected: Set<String>, notes: UserNotes): BundleWriter.Result {
        val root = runCatching { MainActivityRuntime.assetCopyRoot(context) }.getOrNull()
        val redactor = PathRedactor(
            listOfNotNull(
                root?.let { it to "<data-root>" },
                context.getExternalFilesDir(null)?.absolutePath?.let { it to "<app-files>" },
            ),
        )
        val chosen = plan.entries.filter { !it.optional || it.zipPath in selected }
        // Computed from the journal on disk, whether or not the raw files go into the zip.
        val sessionMetrics = SessionMetrics.fromFolder(plan.record?.folder)
        val notStored = plan.record?.state?.framesNotStored?.takeIf { it > 0 }?.let {
            Metric("session.frames_not_stored", it.toDouble(), "frames", "session journal", "intervals past the 16 MB per-session cap; summaries cover the stored ones")
        }
        val unavailableBase = baseUnavailable(plan) + sessionMetrics.unavailable +
            plan.entries.filter { it.optional && it.zipPath !in selected }
                .map { Unavailable(it.zipPath, "deselected by the user") }

        return BundleWriter(redactor).write(out, chosen) { result ->
            DiagnosticReport(
                generatedAtUtc = Instant.now().toString(),
                exportReason = plan.target.name.lowercase(),
                build = buildInfo(context),
                device = deviceInfo(context),
                session = plan.record?.toSessionInfo(),
                game = plan.record?.state?.game,
                driver = plan.record?.state?.driver,
                settings = plan.record?.state?.settings,
                userNotes = UserNotes(
                    scenario = notes.scenario?.trim()?.takeIf { it.isNotEmpty() }?.let(redactor::redact),
                    observations = notes.observations?.trim()?.takeIf { it.isNotEmpty() }?.let(redactor::redact),
                ),
                logDigest = result.digests,
                metrics = sessionMetrics.metrics + listOfNotNull(notStored) + exportTimeMetrics(context),
                files = result.files,
                unavailable = unavailableBase + result.excluded,
            )
        }
    }

    // ---- Collection helpers -------------------------------------------------------------------

    private fun baseUnavailable(plan: Plan): List<Unavailable> = buildList {
        if (plan.record == null) add(Unavailable("session", "no game session has been recorded since diagnostics were added"))
        add(Unavailable("shader_pipeline_compile_stats", "not instrumented in this build; see compile lines in ARMSX3.log"))
        add(Unavailable("vulkan_queue_fence_stats", "not instrumented in this build"))
        add(Unavailable("vulkan_validation_messages", "validation layers are not shipped; driver messages reach ARMSX3.log only when the core logs them"))
        add(Unavailable("logcat", "needs READ_LOGS, which an app can only get through ADB; not collected"))
        add(Unavailable("cpu_gpu_clocks", "no unprivileged API on Android; not collected"))
        add(Unavailable("game.version", "not read from PARAM.SFO yet"))
        add(Unavailable("screenshots", "not offered by this version of the export"))
    }

    private fun buildInfo(context: Context): BuildInfo = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        BuildInfo(
            appId = context.packageName,
            versionName = pi.versionName,
            versionCode = pi.longVersionCode,
            buildType = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debuggable" else "release",
            coreGitVersion = runCatching { com.armsx3.NativeApp.getBuildVersion() }.getOrNull()?.takeIf { it.isNotBlank() },
        )
    }.getOrDefault(BuildInfo(appId = context.packageName))

    private fun deviceInfo(context: Context): DeviceInfo {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            socManufacturer = Build.SOC_MANUFACTURER,
            socModel = Build.SOC_MODEL,
            androidRelease = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mem.totalMem.takeIf { it > 0 },
            isOdin2Portal = Heuristics.isOdin2Portal(Build.MANUFACTURER, Build.MODEL),
        )
    }

    private fun exportTimeMetrics(context: Context): List<Metric> = buildList {
        val now = SystemClock.elapsedRealtime()
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo()
        if (am != null && runCatching { am.getMemoryInfo(mem) }.isSuccess) {
            add(Metric("export.mem_available", mem.availMem.toDouble(), "bytes", "ActivityManager.MemoryInfo", "single sample at export time, not during play", now))
        }
        context.getSystemService(PowerManager::class.java)?.let { pm ->
            add(Metric("export.thermal_status", pm.currentThermalStatus.toDouble(), "PowerManager.THERMAL_STATUS_*", "PowerManager", "single sample at export time; 0 = none, 6 = shutdown", now))
        }
    }

    private fun memoryLine(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java) ?: return "unavailable"
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "avail_mb=${mem.availMem / (1024 * 1024)} threshold_mb=${mem.threshold / (1024 * 1024)} low=${mem.lowMemory}"
    }

    private fun thermalStatus(context: Context): String? =
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus?.let(::thermalName)

    private fun watchThermal(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        unwatchThermal()
        val l = PowerManager.OnThermalStatusChangedListener { status -> event("thermal_status", thermalName(status)) }
        runCatching { pm.addThermalStatusListener(io, l); thermalListener = l }
    }

    private fun unwatchThermal() {
        val l = thermalListener ?: return
        thermalListener = null
        runCatching { app?.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running_moderate"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running_low"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running_critical"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "ui_hidden"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        else -> level.toString()
    }
}
