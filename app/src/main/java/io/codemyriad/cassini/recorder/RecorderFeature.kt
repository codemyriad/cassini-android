package io.codemyriad.cassini.recorder

import android.content.Context
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.adapters.InstalledEngines
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderSettings
import io.codemyriad.cassini.data.recorder.RecorderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The recorder sync of the process (docs/recorder-sync.md), made by [AppGraph.recorder]: the store, the sync, its
 * runner and "Auto sync". Auto sync starts with the feature, which the graph creates at the first ON_START.
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

    val runner = RecorderRunner(graph.appScope, sync, AndroidRecorderServiceStarter(app))

    /** The live notes, as the recorder rows and "Auto sync" read them. */
    val notes: Flow<Map<String, NoteSyncInfo>> = flow {
        emitAll(
            graph.awaitCatalogue().notes.library().map { rows ->
                rows.associate { row ->
                    val transcribed = row.audioKind == AudioKind.DOCUMENT && (row.wordCount ?: 0) > 0
                    row.id to NoteSyncInfo(row.title, row.jobState, transcribed)
                }
            },
        )
    }

    private val autoSync = RecorderAutoSync(
        graph.appScope,
        store.settings,
        runner,
        notes.map { notes -> notes.filterValues { it.transcribed }.keys },
    )

    fun start() = autoSync.start()
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

    override suspend fun setAutoSync(enabled: Boolean) = feature.sync.setAutoSync(enabled)

    override suspend fun bind(device: RecorderDevice, pin: suspend (Boolean) -> String?): BindOutcome =
        feature.sync.bind(device, pin)

    override suspend fun forget(device: RecorderDevice) {
        feature.sync.forget()
        RecorderCompanion.disassociate(app, device.address)
    }
}
