package org.cassini.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cassini.android.adapters.InstalledEngines
import org.cassini.android.adapters.NamingGate
import org.cassini.android.adapters.NetworkCheck
import org.cassini.android.adapters.RepositoryJobStorage
import org.cassini.android.adapters.RepositoryJobVoices
import org.cassini.android.adapters.RepositoryModelAccess
import org.cassini.android.notices.ImportTracker
import org.cassini.android.notices.NoticeCenter
import org.cassini.android.notices.TrashUndo
import org.cassini.android.playback.AndroidPlaybackPlatform
import org.cassini.android.playback.MediaAudioPlayer
import org.cassini.android.playback.PlaybackController
import org.cassini.android.playback.RepositoryPositionStore
import org.cassini.android.processing.AppVisibility
import org.cassini.android.processing.ForegroundServiceStarter
import org.cassini.android.processing.ModelInstallOwner
import org.cassini.android.processing.ProcessVisibility
import org.cassini.android.processing.ProcessingCoordinator
import org.cassini.android.processing.ProcessingJobFactory
import org.cassini.android.processing.ProcessingNotices
import org.cassini.android.processing.ProcessingNotifications
import org.cassini.android.processing.ServiceStarter
import org.cassini.android.processing.TranscriptionJobFactory
import org.cassini.android.recording.AndroidRecordingPlatform
import org.cassini.android.recording.CaptureSource
import org.cassini.android.recording.FactoryCaptureSource
import org.cassini.android.recording.ProcessingHandoff
import org.cassini.android.recording.RecordingConsent
import org.cassini.android.recording.RecordingController
import org.cassini.android.recording.RecordingNotes
import org.cassini.android.recording.RecordingPlatform
import org.cassini.android.recording.RepositoryRecordingNotes
import org.cassini.android.recording.SettingsConsent
import org.cassini.android.recording.TranscriptionHandoff
import org.cassini.core.models.ModelPart
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteLocks
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.CassiniDatabaseOpener
import org.cassini.data.jobs.JobRepository
import org.cassini.data.jobs.ReconcileReport
import org.cassini.data.jobs.RunningJobCanceller
import org.cassini.data.models.ModelFetcher
import org.cassini.data.models.ModelRepository
import org.cassini.data.notes.CatalogueRepository
import org.cassini.data.notes.DocumentRepository
import org.cassini.data.notes.ImportRepository
import org.cassini.data.notes.ImportSource
import org.cassini.data.notes.NoteRepository
import org.cassini.data.notes.RebuildReport
import org.cassini.data.notes.RecoveryRepository
import org.cassini.data.notes.SealedAdopter
import org.cassini.data.notes.SweepReport
import org.cassini.data.settings.SettingsRepository
import org.cassini.data.voices.VoiceRepository
import org.cassini.engine.job.BatterySource
import org.cassini.engine.job.JobEngines
import org.cassini.engine.job.JobEnvironment
import org.cassini.engine.job.JobStorage
import org.cassini.engine.job.JobVoices
import org.cassini.engine.job.ModelAccess
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's manual dependency graph (architecture 2.5): one instance per process, created by [CassiniApplication]
 * at the first ON_START or at the first service start. Tests and the instrumentation runner build one on a scratch [Config.paths].
 *
 * The parts that need no database exist at once: paths, dispatchers, locks, settings, models and the install owner,
 * the engine seams. The parts over Room are in [Catalogue], which exists once the database is open ([catalogue]).
 * The startup work of architecture 4.8 starts on IO when the graph is created; [ready] completes after it. Starting a
 * job, an import or a recording awaits [ready]. When the database cannot be opened (a full disk, an I/O, lock or
 * permission error; architecture 3.3) both complete with that error, the file stays, and the next start tries again.
 */
@Suppress("TooManyFunctions") // wiring: one property or binding per component of architecture 5.1
class AppGraph(context: Context, val config: Config) {
    /**
     * What a graph is built from. [fetcher] fetches model files (tests and the download screen checks pass a scripted
     * one, so nothing is downloaded); [network] answers the validated-network check (null: the connectivity
     * service); [onStartupStep] sees the name of each startup step before it runs, for tests. [processing] and
     * [recording] replace parts of the coordinator and the recording controller in tests.
     */
    data class Config(
        val paths: AppPaths,
        val dispatchers: CassiniDispatchers = CassiniDispatchers(),
        val fetcher: ModelFetcher = ModelFetcher.network(),
        val network: NetworkCheck? = null,
        val clock: () -> Long = System::currentTimeMillis,
        val zone: () -> ZoneId = ZoneId::systemDefault,
        val onStartupStep: (String) -> Unit = {},
        val processing: ProcessingHooks = ProcessingHooks(),
        val recording: RecordingHooks = RecordingHooks(),
    )

    /**
     * The seams of [ProcessingCoordinator] for tests: the job each claim runs, how the service starts, the results
     * notifications, the app's visibility, the battery reading and the API level. Null means the app's implementation.
     */
    data class ProcessingHooks(
        val jobs: ((AppGraph) -> ProcessingJobFactory)? = null,
        val starter: ((AppGraph) -> ServiceStarter)? = null,
        val notices: ProcessingNotices? = null,
        val visibility: AppVisibility? = null,
        val battery: BatterySource? = null,
        val sdkInt: Int = Build.VERSION.SDK_INT,
    )

    /**
     * The seams of [RecordingController] for tests: the capture, the Android services, the rows, the hand-off, the
     * consent and the intervals. Null means the app's implementation.
     */
    data class RecordingHooks(
        val capture: CaptureSource? = null,
        val platform: RecordingPlatform? = null,
        val notes: ((Catalogue) -> RecordingNotes)? = null,
        val handoff: ((AppGraph) -> TranscriptionHandoff)? = null,
        val consent: RecordingConsent? = null,
        val timing: RecordingController.Timing = RecordingController.Timing(),
    )

    private val app: Context = context.applicationContext

    /** The application scope: lives as long as the process (or until [close] in tests). */
    val appScope = CoroutineScope(SupervisorJob() + config.dispatchers.default)

    val paths: AppPaths = config.paths
    val dispatchers: CassiniDispatchers = config.dispatchers
    val locks = NoteLocks()
    val network: NetworkCheck = config.network ?: NetworkCheck.of(app)

    val settings: SettingsRepository =
        SettingsRepository.create(paths, CoroutineScope(appScope.coroutineContext + dispatchers.io))
    val models = ModelRepository(paths, settings, dispatchers, config.fetcher, config.clock)
    val installOwner = ModelInstallOwner(models, paths.modelRoot, network, appScope, dispatchers.io)
    val modelAccess: ModelAccess = RepositoryModelAccess(models, paths.modelRoot, installOwner, network)

    /** The native seams of every job, over the models installed in [AppPaths.modelRoot]. */
    val engines: JobEngines by lazy { InstalledEngines.of(app, paths.modelRoot, paths.cache) }
    val jobEnvironment: JobEnvironment by lazy { InstalledEngines.environment(app) }

    /** Whether the UI may change the draft names of a note; the job closes it at step 6. */
    val namingGate = NamingGate()

    /**
     * The transcription queue (architecture 5.1-5.3), created on first use or when the app is first visible. It binds
     * itself as the canceller of a RUNNING job for the purge and follows the app's visibility (ON_START starts a
     * waiting queue).
     */
    val processing: ProcessingCoordinator by lazy { createProcessing() }

    /** Whether a screen of the app is visible; results notifications are posted only while it is not. */
    val visibility: AppVisibility by lazy {
        config.processing.visibility ?: ProcessVisibility().also(ProcessVisibility::attach)
    }

    /** The single thread of the recording controller (architecture 7). */
    private val recorderThread: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cassini-recorder").apply { isDaemon = true }
    }
    private val recordingHolder = lazy { createRecording() }

    /** The one recording of the process (architecture 5.8), created on first use. */
    val recording: RecordingController by recordingHolder

    /**
     * The banners' facts (architecture 5.1, `NoticeCenter`) and the imports they report, created on first use. The
     * Library's picker and ACTION_VIEW both import through [NoticeCenter.imports].
     */
    val notices: NoticeCenter by lazy {
        val source = ImportSource.of(app.contentResolver)
        val imports = ImportTracker(
            scope = appScope,
            describe = { uri -> withContext(dispatchers.io) { source.describe(uri.toUri()).displayName } },
            run = { uri -> awaitCatalogue().imports.import(uri.toUri()) },
            clock = config.clock,
            fallbackName = { ContextCompat.getContextForLanguage(app).getString(R.string.import_fallback_title) },
        )
        NoticeCenter(this, app, imports)
    }

    /** "Moved to trash" from the Note screen, for the Library's Undo snackbar. */
    val trashUndo = TrashUndo()

    /** The Note screen's player (architecture 5.9). Main thread only. */
    val playback: PlaybackController by lazy {
        PlaybackController(
            PlaybackController.Parts(
                scope = appScope,
                main = dispatchers.main,
                player = { MediaAudioPlayer(dispatchers.io) },
                platform = AndroidPlaybackPlatform(app),
                store = RepositoryPositionStore { awaitCatalogue().notes },
                clock = SystemClock::elapsedRealtime,
            ),
        )
    }

    private val runningJobCanceller = AtomicReference(RunningJobCanceller { })
    private val activeRecording = AtomicReference<() -> String?> { null }

    private val opened = CompletableDeferred<Catalogue>()
    private val startup = CompletableDeferred<Unit>()
    private val report = MutableStateFlow<StartupReport?>(null)

    @Volatile private var openedCatalogue: Catalogue? = null

    /** The repositories over Room; completes once the database is open (startup step 1). */
    val catalogue: Deferred<Catalogue> get() = opened

    /** Completes after the startup work of architecture 4.8. */
    val ready: Deferred<Unit> get() = startup

    /** What the startup work did (the recovered recordings for their banner, the failures); null until it ends. */
    val startupReport: StateFlow<StartupReport?> = report.asStateFlow()

    init {
        bindActiveRecording { if (recordingHolder.isInitialized()) recording.activeNoteId.value else null }
        appScope.launch(dispatchers.io) { runStartup() }
        // ON_START (architecture 5.3): the first time the app is visible the coordinator exists, so a waiting queue
        // starts also when no screen reads `processing` first.
        appScope.launch {
            visibility.visible.first { it }
            processing
        }
    }

    /**
     * Sets how a RUNNING job is stopped before a purge deletes its files (`JobRepository`, architecture 4.10 step 1).
     * `ProcessingCoordinator` binds its cancel-and-join; until then no job runs, and nothing needs stopping.
     */
    fun bindRunningJobCanceller(canceller: RunningJobCanceller) = runningJobCanceller.set(canceller)

    /**
     * Sets where the startup reads the note that is recording now (`RecordingController.activeNoteId`): recovery and
     * reconcile leave that note alone. Bind it before the first use of [ready]; until then there is none.
     */
    fun bindActiveRecording(noteId: () -> String?) = activeRecording.set(noteId)

    /** A [Clock] over [Config.clock] and [Config.zone], read at each call (the ViewModels' day labels). */
    fun clock(): Clock = ConfigClock(config.clock, config.zone)

    /** The catalogue, waiting until the database is open. */
    suspend fun awaitCatalogue(): Catalogue = opened.await()

    /** Stops the graph's work and closes the database. For tests; the app's graph lives as long as the process. */
    fun close() {
        appScope.cancel()
        recorderThread.shutdownNow()
        openedCatalogue?.database?.close()
    }

    // Startup work (architecture 4.8). ---------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught") // the database open: every failure fails ready and is shown
    private suspend fun runStartup() {
        val (catalogue, setAside) = try {
            config.onStartupStep(STEP_DATABASE)
            val database = CassiniDatabaseOpener(app, paths, config.clock).open()
            Catalogue(app, this, database.database, ::currentCanceller) to database.setAside
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Cannot open the catalogue; the next start tries again", error)
            report.value = StartupReport(failed = mapOf(STEP_DATABASE to error.toString()))
            opened.completeExceptionally(error)
            startup.completeExceptionally(error)
            return
        }
        openedCatalogue = catalogue
        opened.complete(catalogue)
        val failed = LinkedHashMap<String, String>()
        val active = activeRecording.get()
        val rebuild = bestEffort(STEP_REBUILD, failed) { catalogue.rebuilder.rebuildIfNeeded(setAside) }
        bestEffort(STEP_MODELS, failed) { models.refresh() }
        val recovered = bestEffort(STEP_RECOVER, failed) { catalogue.recovery.recover(active()) }
        val reconcile = bestEffort(STEP_RECONCILE, failed) { catalogue.jobs.reconcileOnStart(active()) }
        val links = bestEffort(STEP_LINKS, failed) { catalogue.voices.syncLinks() }
        val purged = bestEffort(STEP_PURGE, failed) {
            val now = config.clock()
            catalogue.notes.purgeExpired(Instant.ofEpochMilli(now), config.zone()).also { settings.recordPurge(now) }
        }
        val sweep = bestEffort(STEP_SWEEP, failed) { catalogue.documents.sweep() }
        val retitled = bestEffort(STEP_RETITLE, failed) { catalogue.documents.retitleDirty() }
        report.value = StartupReport(
            rebuild = rebuild,
            recovered = recovered.orEmpty(),
            reconcile = reconcile,
            links = links,
            purged = purged ?: 0,
            sweep = sweep,
            retitled = retitled.orEmpty(),
            failed = failed,
        )
        startup.complete(Unit)
    }

    /** Runs one startup step; a failure is logged and recorded, and the next step still runs. */
    @Suppress("TooGenericExceptionCaught") // each step is best effort; the next start runs it again
    private suspend fun <T> bestEffort(name: String, failed: MutableMap<String, String>, block: suspend () -> T): T? {
        config.onStartupStep(name)
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Startup step $name failed; the next start runs it again", error)
            failed[name] = error.toString()
            null
        }
    }

    private fun currentCanceller(): RunningJobCanceller = runningJobCanceller.get()

    private fun createProcessing(): ProcessingCoordinator {
        val hooks = config.processing
        val created = AtomicReference<ProcessingCoordinator?>(null)
        val starter = hooks.starter?.invoke(this)
            ?: ForegroundServiceStarter(app) { created.get()?.onServiceRefused() }
        val parts = ProcessingCoordinator.Parts(
            jobs = hooks.jobs?.invoke(this) ?: TranscriptionJobFactory(this),
            starter = starter,
            notices = hooks.notices ?: ProcessingNotifications(app),
            visibility = visibility,
            battery = hooks.battery ?: BatterySource { elapsedMs -> BatteryReader.read(app, elapsedMs) },
            placeholderLabel = { number -> InstalledEngines.speakerLabel(app, number) },
            sdkInt = hooks.sdkInt,
        )
        val coordinator = ProcessingCoordinator(this, parts)
        created.set(coordinator)
        bindRunningJobCanceller { noteId -> coordinator.cancelAndJoin(noteId) }
        return coordinator
    }

    private fun createRecording(): RecordingController {
        val hooks = config.recording
        val handoff = hooks.handoff?.invoke(this) ?: ProcessingHandoff(
            coordinator = { processing },
            settings = settings,
            starter = ForegroundServiceStarter(app) { processing.onServiceRefused() },
            main = dispatchers.main,
        )
        val parts = RecordingController.Parts(
            scope = appScope,
            dispatcher = recorderThread.asCoroutineDispatcher(),
            ready = { ready.await() },
            notes = {
                val catalogue = awaitCatalogue()
                hooks.notes?.invoke(catalogue) ?: RepositoryRecordingNotes(catalogue.notes, paths::note)
            },
            capture = hooks.capture ?: FactoryCaptureSource(app),
            platform = hooks.platform ?: AndroidRecordingPlatform(app),
            handoff = handoff,
            consent = hooks.consent ?: SettingsConsent(models, settings),
            defaultTitle = ::defaultTitle,
            clock = config.clock,
            timing = hooks.timing,
        )
        return RecordingController(parts)
    }

    /** "Voice note 8 Oct, 08:14" in the app language (features.md section 2). */
    private fun defaultTitle(createdAt: Long): String {
        val localized = ContextCompat.getContextForLanguage(app)
        val locale = localized.resources.configuration.locales[0] ?: Locale.getDefault()
        val time = Instant.ofEpochMilli(createdAt).atZone(config.zone())
        val date = DateTimeFormatter.ofPattern(TITLE_DATE_PATTERN, locale).format(time).replace(".", "")
        return localized.getString(R.string.rec_default_title, date)
    }

    companion object {
        private const val TAG = "AppGraph"
        private const val TITLE_DATE_PATTERN = "d MMM, HH:mm"

        // The startup steps in their order (architecture 4.8), as Config.onStartupStep names them.
        const val STEP_DATABASE = "database"
        const val STEP_REBUILD = "rebuild"
        const val STEP_MODELS = "models"
        const val STEP_RECOVER = "recover"
        const val STEP_RECONCILE = "reconcile"
        const val STEP_LINKS = "links"
        const val STEP_PURGE = "purge"
        const val STEP_SWEEP = "sweep"
        const val STEP_RETITLE = "retitle"
    }
}

/**
 * The repositories over the open Room catalogue, and the job's storage ports over them (architecture 2.3, 4 and 5.4).
 * [AppGraph.catalogue] gives the one instance.
 */
class Catalogue internal constructor(
    context: Context,
    graph: AppGraph,
    val database: CassiniDatabase,
    canceller: () -> RunningJobCanceller,
) {
    private val paths = graph.paths
    private val dispatchers = graph.dispatchers
    private val locks = graph.locks
    private val clock = graph.config.clock

    val documents = DocumentRepository(database, paths, dispatchers, locks, context.contentResolver, clock = clock)
    val checkpoints = CheckpointRepository(database, paths, dispatchers, locks)
    val voices = VoiceRepository(database, paths, dispatchers, checkpoints.names, clock)

    /** Finishes an interrupted adopt: the document, then the voices, then `work/` (architecture 4.1). */
    val adopter: SealedAdopter = SealedAdopter.of(documents, voices)
    val jobs = JobRepository(
        database,
        paths,
        dispatchers,
        locks,
        checkpoints,
        adopter,
        RunningJobCanceller { noteId -> canceller().cancelAndJoin(noteId) },
        clock,
    )
    val notes = NoteRepository(database, paths, dispatchers, locks, documents, graph.appScope, jobs, voices, clock)
    val imports = ImportRepository(
        database,
        paths,
        dispatchers,
        locks,
        documents,
        ImportSource.of(context.contentResolver),
        // Read at each import, in the app language of that moment (also below API 33).
        fallbackTitle = { ContextCompat.getContextForLanguage(context).getString(R.string.import_fallback_title) },
        clock = clock,
    )
    val recovery = RecoveryRepository(database, paths, dispatchers, locks, clock = clock)
    val rebuilder = CatalogueRepository(database, paths, dispatchers, locks, documents, voices, clock = clock)

    /** The storage ports of `TranscriptionJob` (architecture 5.4). */
    val jobStorage: JobStorage = RepositoryJobStorage(
        paths,
        locks,
        notes,
        documents,
        checkpoints,
        graph.settings,
        adopter,
        graph.namingGate,
        dispatchers.io,
    )
    val jobVoices: JobVoices = RepositoryJobVoices(voices, notes, documents)
}

/** What the startup work of architecture 4.8 did. [failed] maps a step name to its error. */
data class StartupReport(
    val rebuild: RebuildReport? = null,
    /** The recordings that recovery repaired, for the "recording recovered" banner. */
    val recovered: List<String> = emptyList(),
    val reconcile: ReconcileReport? = null,
    val links: VoiceRepository.LinkSync? = null,
    val purged: Int = 0,
    val sweep: SweepReport? = null,
    val retitled: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
)

/**
 * Where the app keeps its files (architecture 2.5 and 3.2): `filesDir` and `cacheDir`, or in a debug build a scratch
 * root named by `files/debug-root.txt` that survives process death (the kill-and-resume device check, section 9).
 * The models stay at `filesDir` either way, so a scratch root reuses the installed models.
 */
object AppRoots {
    const val DEBUG_ROOT_FILE = "debug-root.txt"

    /** The default and reserved names at the top of `filesDir`, which a scratch root must not be. */
    private val reserved: Set<String> =
        setOf("notes", "voices") + ModelPart.entries.flatMap { part -> part.models.map { it.directory } }

    /**
     * The paths of this process. [debug] is `BuildConfig.DEBUG`: in a release build `debug-root.txt` is never read.
     * A missing, empty or unusable file gives the default paths.
     */
    fun resolve(filesDir: File, cacheDir: File, debug: Boolean): AppPaths {
        val scratch = if (debug) debugRoot(filesDir) else null
        return if (scratch == null) {
            AppPaths(root = filesDir, cache = cacheDir)
        } else {
            AppPaths(root = scratch, cache = File(cacheDir, scratch.name), modelRoot = filesDir)
        }
    }

    /**
     * The scratch root that `files/debug-root.txt` names: a path relative to `filesDir` (or absolute inside it), one
     * level or more below it, that is not one of the app's own directories. Null otherwise.
     */
    fun debugRoot(filesDir: File): File? {
        val file = File(filesDir, DEBUG_ROOT_FILE)
        val name = try {
            if (file.isFile) file.readText().trim() else ""
        } catch (_: IOException) {
            ""
        }
        if (name.isEmpty()) return null
        val base = filesDir.canonicalFile
        val target = (if (File(name).isAbsolute) File(name) else File(base, name)).canonicalFile
        val top = generateSequence(target) { it.parentFile }.firstOrNull { it.parentFile == base }
        return target.takeIf { top != null && top.name !in reserved && top.name != DEBUG_ROOT_FILE }
    }
}

/** A [Clock] that reads [millis] and [zone] at every call, so a zone change takes effect at once. */
private class ConfigClock(private val millis: () -> Long, private val zone: () -> ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone()

    override fun withZone(zone: ZoneId): Clock = ConfigClock(millis) { zone }

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun millis(): Long = millis.invoke()
}
