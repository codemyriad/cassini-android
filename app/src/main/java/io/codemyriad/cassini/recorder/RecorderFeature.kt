package io.codemyriad.cassini.recorder

import android.content.Context
import android.util.Log
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.adapters.InstalledEngines
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.NoteFacts
import io.codemyriad.cassini.core.library.NoteStatus
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderSettings
import io.codemyriad.cassini.data.recorder.RecorderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The recorder sync of the process (docs/recorder-sync.md), made by [AppGraph.recorder]: the store, the sync, its
 * runner, "Auto sync" and the transcript push. Both start with the feature, which the graph creates at the first ON_START
 * while "Voice recorder" is on (Settings › Experimental features, D-1178), and stop when it is turned off.
 */
class RecorderFeature(
    context: Context,
    private val graph: AppGraph,
    radio: RecorderRadio? = null,
    vault: KeyVault? = null,
) {
    private val app = context.applicationContext

    val store = RecorderStore(File(File(graph.paths.root, "recorder"), "recorder.json"), graph.dispatchers.io)
    val radio: RecorderRadio = radio ?: BluetoothRecorderRadio(app, graph.dispatchers.io)

    val sync = RecorderSync(
        RecorderSync.Parts(
            store = store,
            radio = this.radio,
            vault = vault ?: KeystoreKeyVault(),
            library = GraphRecorderLibrary(graph) { number -> InstalledEngines.speakerLabel(app, number) },
            downloads = File(graph.paths.cache, "recorder"),
            io = graph.dispatchers.io,
            clock = graph.config.clock,
            zone = graph.config.zone,
        ),
    )

    val runner = RecorderRunner(
        graph.appScope,
        sync,
        onRebindNeeded = { RecorderNotifications.notifyRebindNeeded(app) },
        service = AndroidRecorderServiceStarter(app),
    )

    /** The live notes, as the recorder rows and "Auto sync" read them. */
    val notes: Flow<Map<String, NoteSyncInfo>> = flow {
        emitAll(
            graph.awaitCatalogue().notes.library().map { rows ->
                rows.associate { row -> row.id to RecorderNotes.info(row) }
            },
        )
    }

    private val lifecycle = Any()
    private val presence = Mutex()

    /** The work of the running feature (auto sync, transcript push); null before [start] and after [stop]. */
    private var running: Job? = null

    /** Whether [start] ran and [stop] did not follow. */
    val active: Boolean get() = synchronized(lifecycle) { running?.isActive == true }

    /** Starts "Auto sync", the transcript push and the companion presence observation; again after [stop]. */
    fun start() {
        synchronized(lifecycle) {
            if (running?.isActive == true) return
            val job = SupervisorJob(graph.appScope.coroutineContext.job)
            running = job
            val scope = CoroutineScope(graph.appScope.coroutineContext + job)
            RecorderAutoSync(scope, store.settings, runner).start()
            RecorderTranscriptPush(
                scope,
                store.settings,
                runner,
                notes.map(RecorderNotes::pushable),
                sync::pendingUploads,
            ).start()
        }
        graph.appScope.launch(graph.dispatchers.io) {
            ensurePresenceObserved("start")
        }
    }

    /**
     * "Voice recorder" turned off (D-1178): ends "Auto sync" and the transcript push and stops the companion presence
     * observation. `recorder/recorder.json`, the binding and the imported notes stay; [start] resumes them.
     */
    fun stop() {
        val job = synchronized(lifecycle) { running.also { running = null } } ?: return
        job.cancel()
        graph.appScope.launch(graph.dispatchers.io) {
            ensurePresenceObserved("stop")
        }
    }

    /**
     * Ensures companion presence observation matches the bound recorder, "Auto sync" and whether the feature runs
     * ([active]). One change at a time, so the last call wins after a quick off and on.
     */
    suspend fun ensurePresenceObserved(reason: String) = presence.withLock {
        val settings = store.read()
        val address = settings.binding?.device?.address ?: return@withLock
        if (active && settings.autoSync && settings.current?.rebindNeeded != true) {
            Log.i(TAG, "Ensuring companion presence observation for $address ($reason)")
            RecorderCompanion.observePresence(app, address)
        } else {
            RecorderCompanion.stopObservingPresence(app, address)
        }
    }

    /**
     * Woken when [address] (null: any associated recorder) connected over Bluetooth ([reason]: `companion_appeared` or
     * `acl_connected`). Starts a full sync when [address] is the bound recorder and "Auto sync" is on.
     */
    fun onDevicePresent(address: String?, reason: String) {
        start()
        if (!sync.canAcceptFullSync()) return
        graph.appScope.launch(graph.dispatchers.io) {
            if (!sync.canAcceptFullSync()) return@launch
            val settings = store.read()
            val bound = settings.binding?.device?.address ?: return@launch
            if (address != null && !address.equals(bound, ignoreCase = true)) return@launch
            if (!settings.autoSync || settings.current?.rebindNeeded == true) {
                Log.i(TAG, "Ignoring $reason for $bound (autoSync=${settings.autoSync})")
                return@launch
            }
            Log.i(TAG, "Recorder $bound present ($reason); starting wake sync")
            runner.wakeAttempt().await()
        }
    }

    private companion object {
        const val TAG = "Recorder"
    }
}

/** The recorder's view of the Library rows. */
object RecorderNotes {
    /**
     * [row] for the recorder rows: transcribed when its document has words; no speech when the Library shows it as
     * transcribed with 0 words ("· No speech", [NoteStatus.Suffix.NO_SPEECH]).
     */
    fun info(row: LibraryRow): NoteSyncInfo {
        val words = row.wordCount ?: 0
        val transcribed = row.audioKind == AudioKind.DOCUMENT && words > 0
        val noSpeech = words == 0 && NoteStatus.of(NoteFacts(row.audioKind, row.documentState)) == NoteStatus.TRANSCRIBED
        return NoteSyncInfo(row.title, row.jobState, transcribed, noSpeech)
    }

    /** The notes whose transcript sidecar goes to the recorder (both speech and 0-word `"No speech"` sidecars). */
    fun pushable(notes: Map<String, NoteSyncInfo>): Set<String> =
        notes.filterValues { it.transcribed || it.noSpeech }.keys
}

/** [RecorderScreenPorts] over the graph's [RecorderFeature]. */
class GraphRecorderScreenPorts(context: Context, private val feature: RecorderFeature) : RecorderScreenPorts {
    private val app = context.applicationContext

    override val settings: Flow<RecorderSettings> get() = feature.store.settings
    override val activity: StateFlow<RecorderActivity> get() = feature.sync.activity
    override val notes: Flow<Map<String, NoteSyncInfo>> get() = feature.notes
    override val companionSupported: Boolean = RecorderCompanion.supported(app)

    override fun readiness(): RadioReadiness = feature.radio.readiness()

    override fun bondedRecorders(): List<RecorderDevice> =
        (feature.radio as? BluetoothRecorderRadio)?.bondedRecorders().orEmpty()

    override fun syncNow() {
        feature.runner.syncNow()
    }

    override suspend fun setAutoSync(enabled: Boolean) {
        feature.sync.setAutoSync(enabled)
        feature.ensurePresenceObserved("setAutoSync")
    }

    override suspend fun setSyncWhileRecording(enabled: Boolean) = feature.sync.setSyncWhileRecording(enabled)

    override suspend fun bind(device: RecorderDevice, pin: suspend (Boolean) -> String?): BindOutcome =
        feature.sync.bind(device, pin).also { outcome ->
            if (outcome == BindOutcome.Bound) feature.ensurePresenceObserved("bind")
        }

    override suspend fun forget(device: RecorderDevice) {
        feature.sync.forget()
        RecorderCompanion.disassociate(app, device.address)
    }
}
