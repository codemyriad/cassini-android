package io.codemyriad.cassini.data.recorder

import android.util.AtomicFile
import android.util.Log
import io.codemyriad.cassini.recorder.PartialDownload
import io.codemyriad.cassini.recorder.RecorderSyncState
import io.codemyriad.cassini.recorder.SyncedRecording
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A Cassini recorder: its Bluetooth [address] and the [name] it announces (`Cassini_XX`). */
data class RecorderDevice(val address: String, val name: String)

/**
 * The bound recorder. [wrappedKey] is the 32-byte binding key, encrypted with a key of the Android Keystore that never
 * leaves it (Base64 of the IV and the AES-GCM output). The plain key is never stored.
 */
data class RecorderBinding(val device: RecorderDevice, val wrappedKey: String)

/**
 * One recording of a recorder as the store keeps it: the planner's [recording], the reason of a failed import, and
 * whether the last sync that listed the recorder's files did not find it ([absent]: deleted, or the card was wiped).
 * The record of an absent recording stays, so its file is not imported again if it comes back, but no transcript waits
 * for it.
 */
data class StoredRecording(val recording: SyncedRecording, val failure: String? = null, val absent: Boolean = false)

/**
 * How the last sync attempt ended, at [at] (ms since the epoch). [outcome] is a stable code that the app maps to
 * text (`OK`, `UNREACHABLE`, `REBIND`, or a failure name); [detail] is a diagnostic, never shown as is.
 */
data class LastSync(
    val at: Long,
    val outcome: String,
    val detail: String? = null,
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    /** The bytes the sync downloaded, and in how long: the screen shows the speed. 0 when none. */
    val downloadedBytes: Long = 0,
    val downloadMillis: Long = 0,
)

/**
 * What the app remembers about one recorder: the imported [recordings], a stopped download ([partial]), the last
 * sync, whether the recorder refused the binding key ([rebindNeeded]), and the firmware that refused transcript
 * uploads ([transcriptsUnsupportedFirmware]) at [transcriptsUnsupportedAt], so the app tells it once and asks again only
 * after a firmware change or a day (`TranscriptUploadProbe`).
 */
data class RecorderDeviceState(
    val recordings: List<StoredRecording> = emptyList(),
    val partial: PartialDownload? = null,
    val lastSync: LastSync? = null,
    val lastSuccessAt: Long? = null,
    val rebindNeeded: Boolean = false,
    val transcriptsUnsupportedFirmware: String? = null,
    val transcriptsUnsupportedAt: Long? = null,
) {
    /** The planner's view of this state. */
    fun syncState(): RecorderSyncState = RecorderSyncState(recordings.map { it.recording }, partial)
}

/**
 * Everything about recorders: the bound one ([binding]), the "Auto sync" switch and the sync state of each recorder
 * by address. The sync state stays after "Forget recorder", so a recorder bound again does not import its files again.
 */
data class RecorderSettings(
    val binding: RecorderBinding? = null,
    val autoSync: Boolean = false,
    val devices: Map<String, RecorderDeviceState> = emptyMap(),
) {
    /** The state of the bound recorder; null when no recorder is bound. */
    val current: RecorderDeviceState?
        get() = binding?.let { devices[it.device.address] ?: RecorderDeviceState() }
}

/**
 * The recorder settings and sync state, in one JSON file written with [AtomicFile] (`recorder/recorder.json` under the
 * app root). The file is not in the backup allow-list: the wrapped key works only with this phone's Keystore, and the
 * sync state belongs to this phone's library. Every change runs under one lock and is written before [update]
 * returns, so a download that stops with the process resumes from the stored state.
 *
 * An unreadable file reads as empty settings (logged): it is kept as `recorder.json.bad` for a manual recovery, the next
 * change writes a new file, and the recorder must be bound again. A sync after that binding copies the recordings of
 * the recorder again; the library takes a recording that has a live note as that note, but a recording whose note the
 * user deleted comes back as a new note.
 */
class RecorderStore(private val file: File, private val io: CoroutineDispatcher) {
    private val lock = Mutex()
    private val cache = MutableStateFlow<RecorderSettings?>(null)

    /** The current settings; the file is read at the first collection. */
    val settings: Flow<RecorderSettings> =
        cache.onStart { if (cache.value == null) read() }.filterNotNull().distinctUntilChanged()

    suspend fun read(): RecorderSettings = cache.value ?: lock.withLock { loaded() }

    /** Changes the settings with [change] and writes them; returns the new settings. */
    suspend fun update(change: (RecorderSettings) -> RecorderSettings): RecorderSettings = lock.withLock {
        val current = loaded()
        val next = change(current)
        if (next != current) {
            // Not cancellable: a change on disk is a change in memory too, or the next update writes over it.
            withContext(NonCancellable + io) {
                write(next)
                cache.value = next
            }
        }
        next
    }

    /** Changes the state of the recorder [address] with [change]. */
    suspend fun updateDevice(
        address: String,
        change: (RecorderDeviceState) -> RecorderDeviceState,
    ): RecorderSettings = update { settings ->
        val state = change(settings.devices[address] ?: RecorderDeviceState())
        settings.copy(devices = settings.devices + (address to state))
    }

    private suspend fun loaded(): RecorderSettings = cache.value ?: withContext(io) { readFile() }.also {
        cache.value = it
    }

    private fun readFile(): RecorderSettings = try {
        val bytes = AtomicFile(file).openRead().use { it.readBytes() }
        if (bytes.size > MAX_BYTES) throw IOException("Oversized recorder state")
        RecorderJson.decode(JSONObject(String(bytes, Charsets.UTF_8)))
    } catch (_: FileNotFoundException) {
        RecorderSettings()
    } catch (error: IOException) {
        unreadable(error)
    } catch (error: JSONException) {
        unreadable(error)
    }

    private fun unreadable(error: Exception): RecorderSettings {
        val kept = File(file.parentFile, file.name + BAD_SUFFIX)
        val moved = file.renameTo(kept)
        Log.w(TAG, "The recorder state cannot be read; it starts empty (old file kept: $moved)", error)
        return RecorderSettings()
    }

    private fun write(settings: RecorderSettings) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(RecorderJson.encode(settings).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: IOException) {
            atomic.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val TAG = "RecorderStore"
        const val MAX_BYTES = 4 * 1024 * 1024

        /** The name suffix of an unreadable state file that the store put aside. */
        const val BAD_SUFFIX = ".bad"
    }
}

/** The JSON form of [RecorderSettings], version 1. Unknown members are ignored; a missing one takes its default. */
internal object RecorderJson {
    private const val VERSION = 1

    fun encode(settings: RecorderSettings): JSONObject {
        val devices = JSONObject()
        settings.devices.forEach { (address, state) -> devices.put(address, encode(state)) }
        val json = JSONObject().put("version", VERSION).put("autoSync", settings.autoSync).put("devices", devices)
        settings.binding?.let { binding ->
            json.put(
                "binding",
                JSONObject().put("address", binding.device.address).put("name", binding.device.name)
                    .put("wrappedKey", binding.wrappedKey),
            )
        }
        return json
    }

    fun decode(json: JSONObject): RecorderSettings {
        if (json.optInt("version") != VERSION) throw JSONException("Unknown recorder state version")
        val binding = json.optJSONObject("binding")?.let {
            RecorderBinding(RecorderDevice(it.getString("address"), it.getString("name")), it.getString("wrappedKey"))
        }
        val devices = json.optJSONObject("devices") ?: JSONObject()
        return RecorderSettings(
            binding = binding,
            autoSync = json.optBoolean("autoSync", false),
            devices = devices.keys().asSequence().associateWith { decodeDevice(devices.getJSONObject(it)) },
        )
    }

    private fun encode(state: RecorderDeviceState): JSONObject {
        val recordings = JSONArray()
        state.recordings.forEach { stored ->
            val recording = stored.recording
            recordings.put(
                JSONObject().put("name", recording.name).put("size", recording.size)
                    .putOpt("noteId", recording.noteId).putOpt("headSha256", recording.headSha256)
                    .putOpt("uploadedVersion", recording.transcriptUploadedVersion)
                    .putOpt("refusedVersion", recording.transcriptRefusedVersion).putOpt("failure", stored.failure)
                    .putOpt("absent", stored.absent.takeIf { it }),
            )
        }
        val json = JSONObject().put("recordings", recordings).put("rebindNeeded", state.rebindNeeded)
            .putOpt("transcriptsUnsupportedFirmware", state.transcriptsUnsupportedFirmware)
            .putOpt("transcriptsUnsupportedAt", state.transcriptsUnsupportedAt)
            .putOpt("lastSuccessAt", state.lastSuccessAt)
        state.partial?.let {
            json.put("partial", JSONObject().put("name", it.name).put("size", it.size).put("bytes", it.bytes))
        }
        state.lastSync?.let {
            json.put(
                "lastSync",
                JSONObject().put("at", it.at).put("outcome", it.outcome).putOpt("detail", it.detail)
                    .put("downloaded", it.downloaded).put("uploaded", it.uploaded)
                    .put("downloadedBytes", it.downloadedBytes).put("downloadMillis", it.downloadMillis),
            )
        }
        return json
    }

    private fun decodeDevice(json: JSONObject): RecorderDeviceState {
        val recordings = json.optJSONArray("recordings") ?: JSONArray()
        return RecorderDeviceState(
            recordings = (0 until recordings.length()).map { decodeRecording(recordings.getJSONObject(it)) },
            partial = json.optJSONObject("partial")?.let {
                PartialDownload(it.getString("name"), it.getLong("size"), it.getLong("bytes"))
            },
            lastSync = json.optJSONObject("lastSync")?.let {
                LastSync(
                    it.getLong("at"),
                    it.getString("outcome"),
                    it.optStringOrNull("detail"),
                    it.optInt("downloaded"),
                    it.optInt("uploaded"),
                    it.optLong("downloadedBytes"),
                    it.optLong("downloadMillis"),
                )
            },
            lastSuccessAt = json.optLongOrNull("lastSuccessAt"),
            rebindNeeded = json.optBoolean("rebindNeeded", false),
            transcriptsUnsupportedFirmware = json.optStringOrNull("transcriptsUnsupportedFirmware"),
            transcriptsUnsupportedAt = json.optLongOrNull("transcriptsUnsupportedAt"),
        )
    }

    private fun decodeRecording(json: JSONObject) = StoredRecording(
        SyncedRecording(
            name = json.getString("name"),
            size = json.getLong("size"),
            noteId = json.optStringOrNull("noteId"),
            headSha256 = json.optStringOrNull("headSha256"),
            transcriptUploadedVersion = json.optStringOrNull("uploadedVersion"),
            transcriptRefusedVersion = json.optStringOrNull("refusedVersion"),
        ),
        failure = json.optStringOrNull("failure"),
        absent = json.optBoolean("absent", false),
    )

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
}
