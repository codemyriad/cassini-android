package org.cassini.android.notices

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cassini.android.AppGraph
import org.cassini.android.processing.InstallState
import org.cassini.android.processing.ProcessingState
import org.cassini.android.recording.CaptureFormat
import org.cassini.core.models.ModelPart
import org.cassini.data.db.LibraryRow
import org.cassini.data.models.ModelStatus
import org.cassini.data.voices.VoiceProblem
import org.cassini.engine.job.InstallOutcome
import org.cassini.ui.banner.BannerKind

/**
 * Merges the coordinator, the recorder, the startup report (recovered recordings), the models, the settings, the
 * imports and the notification permission into one [NoticeFacts] (architecture 5.1). The screens turn it into their
 * banner lists with [Notices]. One instance per process (`AppGraph.notices`).
 *
 * The notices the user closes are remembered where features.md says: RECOVERED in `notices.recoveredAck`, NO_ENCODER
 * in `notices.opusNativeAck`, an import error and a download failure until the next import or download.
 */
class NoticeCenter(private val graph: AppGraph, context: Context, val imports: ImportTracker) {
    private val app = context.applicationContext
    private val settings = graph.settings
    private val notificationsOff = MutableStateFlow(!notificationsEnabled())
    private val downloadFailure = MutableStateFlow<DownloadFailure?>(null)

    /** Whether `libcassini_opus` loads (sealing needs it); probed once per process. */
    private val encoderLoads: Boolean by lazy { OpusLibrary.loads() }

    private val library: Flow<List<LibraryRow>> = flow {
        emitAll(graph.awaitCatalogue().notes.library())
    }.catch { error -> Log.w(TAG, "The library cannot be read for the notices", error) }

    private val voicesProblem: Flow<VoiceProblem?> = flow {
        emitAll(graph.awaitCatalogue().voices.people().map { it.problem })
    }.catch { error ->
        Log.w(TAG, "The saved voices cannot be read for the notices", error)
        emit(null)
    }

    private val recovered: Flow<RecoveredNotice?> =
        combine(graph.startupReport, library, settings.recoveredAck) { report, rows, ack ->
            val ids = report?.recovered.orEmpty().toSet()
            val fresh = rows.filter { it.id in ids && it.createdAt > ack }
            val latest = fresh.maxByOrNull { it.createdAt } ?: return@combine null
            RecoveredNotice(fresh.size, latest.id, latest.durationMs, latest.createdAt)
        }

    private val consented: Flow<Boolean> =
        combine(settings.consentAt, graph.models.status) { consentAt, status ->
            consentAt != null || status[ModelPart.SPEECH] == ModelStatus.Ready
        }

    private val encoder: Flow<Pair<Boolean, Boolean>> =
        combine(settings.opusNativeAck, settings.lastRecordingFormat) { acknowledged, format ->
            (!acknowledged && !encoderLoads) to (format == CaptureFormat.M4A.extension)
        }

    private val processing: Flow<ProcessingState> = flow { emitAll(graph.processing.state) }

    /** Every fact the banners need; shared while a screen shows banners. */
    val facts: StateFlow<NoticeFacts> = combine(
        combine(processing, recovered, consented, encoder) { state, recovered, consented, encoder ->
            NoticeFacts(
                hold = state.hold != null,
                queued = state.queue.size,
                jobRunning = state.running != null || state.downloading,
                recovered = recovered,
                consented = consented,
                encoderMissing = encoder.first,
                m4a = encoder.second,
            )
        },
        notificationsOff,
        imports.state,
        downloadFailure,
        voicesProblem.distinctUntilChanged(),
    ) { facts, off, imports, download, voices ->
        facts.copy(notificationsOff = off, imports = imports, downloadFailure = download, voicesProblem = voices)
    }.stateIn(graph.appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NoticeFacts())

    init {
        graph.appScope.launch { followDownloads() }
    }

    /** Reads the notification permission again (a screen calls it at ON_RESUME, after the system settings). */
    fun refreshNotifications() {
        notificationsOff.value = !notificationsEnabled()
    }

    /** The user closed the banner of [kind], or acted on it in a way that ends it. */
    fun dismiss(kind: BannerKind) {
        when (kind) {
            BannerKind.RECOVERED -> graph.appScope.launch {
                guarded("acknowledge the recovered recordings") {
                    facts.value.recovered?.let { settings.acknowledgeRecovered(it.latestAt) }
                }
            }
            BannerKind.NO_ENCODER -> graph.appScope.launch {
                guarded("acknowledge the missing encoder") { settings.acknowledgeOpusNative() }
            }
            BannerKind.IMPORT_TOO_LARGE, BannerKind.IMPORT_NO_SPACE, BannerKind.IMPORT_WAV, BannerKind.IMPORT_OPEN ->
                imports.dismissFailure()
            BannerKind.DL_NO_CONNECTION, BannerKind.DL_STOPPED, BannerKind.DL_NO_SPACE, BannerKind.DL_FILE_CHECK,
            BannerKind.DL_INSTALL_FAILED,
            -> downloadFailure.value = null
            else -> Unit
        }
    }

    /**
     * Keeps [downloadFailure] for the standalone download: a new install clears it, and the end of a standalone one
     * sets it when the speech part failed. A job's download failure is the job's own banner on the Note screen.
     */
    private suspend fun followDownloads() {
        var standalone = false
        graph.installOwner.state.collect { state ->
            when (state) {
                is InstallState.Installing -> {
                    downloadFailure.value = null
                    standalone = standalone || state.standalone
                }
                InstallState.Idle -> {
                    val outcome = graph.installOwner.lastOutcome.value
                    if (standalone && outcome is InstallOutcome.Failed) {
                        downloadFailure.value =
                            DownloadFailure(outcome.error, outcome.requiredFreeBytes, graph.config.clock())
                    }
                    standalone = false
                }
            }
        }
    }

    private fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled()

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Cannot $what", error)
        }
    }

    private companion object {
        const val TAG = "CassiniNotices"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Whether `libcassini_opus` can be loaded: the Cassini document writer needs it (features.md 13.5 NO_ENCODER). */
internal object OpusLibrary {
    fun loads(): Boolean = try {
        System.loadLibrary("cassini_opus")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }
}
