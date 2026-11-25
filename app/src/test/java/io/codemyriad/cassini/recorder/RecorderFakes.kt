package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.StreamXrecInput
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecInput
import io.codemyriad.cassini.core.recorder.XrecTimeouts
import io.codemyriad.cassini.data.recorder.RecorderBinding
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderStore
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.Collections

// The fakes of the recorder sync tests: a radio over FakeRecorder (the recorder's XREC state machine behind pipes), a
// vault without the Keystore and a library that records what the sync asks for.

internal val DEVICE = RecorderDevice("00:11:22:33:44:55", "Cassini_55")

/** Short waits: the fake recorder answers at once, and a lost frame should cost little test time. */
internal val TEST_TIMEOUTS = XrecTimeouts(replyMillis = 2_000, frameMillis = 1_000, quietMillis = 50, drainMillis = 500)

/** A radio whose recorder is [device]; each [open] after the first is a new link to the same recorder. */
internal class FakeRadio(var device: FakeRecorder) : RecorderRadio {
    @Volatile var readiness = RadioReadiness.READY

    @Volatile var reachable = true

    @Volatile var bondOk = true
    var opens = 0
        private set

    override fun readiness(): RadioReadiness = readiness

    override suspend fun bond(address: String): Boolean = bondOk

    override suspend fun open(address: String): RecorderLink {
        if (!reachable) throw IOException("No answer")
        if (opens++ > 0) device = device.reconnect()
        val recorder = device
        return object : RecorderLink {
            private val stream = StreamXrecInput(recorder.clientInput, threadName = "test-link")
            override val input: XrecInput = stream
            override val output: OutputStream = recorder.clientOutput

            override fun close() {
                recorder.clientOutput.close()
                stream.close()
            }
        }
    }
}

/** "Wraps" a key as hex with a prefix; anything else does not unwrap, as a lost Keystore key. */
internal class FakeVault : KeyVault {
    override fun wrap(key: ByteArray): String = PREFIX + XrecAuth.toHex(key)

    override fun unwrap(wrapped: String): ByteArray {
        if (!wrapped.startsWith(PREFIX)) throw GeneralSecurityException("Not a wrapped key")
        return XrecAuth.fromHex(wrapped.removePrefix(PREFIX))
    }

    companion object {
        const val PREFIX = "wrapped:"
    }
}

/** A library that keeps the imported bytes; the same bytes again are an existing note. */
internal class FakeLibrary : RecorderLibrary {
    val imported: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
    val titles: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val transcribed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ready: MutableMap<String, RecorderTranscript> = Collections.synchronizedMap(LinkedHashMap())

    @Volatile var reject: String? = null

    override suspend fun import(file: File, displayName: String): LibraryImport {
        val bytes = file.readBytes()
        val existing = imported.entries.firstOrNull { it.value.contentEquals(bytes) }?.key
        return when {
            reject != null -> LibraryImport.Rejected(reject!!)
            existing != null -> LibraryImport.Existing(existing)
            else -> {
                val noteId = "note-${imported.size + 1}"
                imported[noteId] = bytes
                titles[noteId] = displayName
                LibraryImport.Created(noteId)
            }
        }
    }

    override suspend fun transcribe(noteId: String) {
        transcribed += noteId
    }

    override suspend fun transcripts(noteIds: Collection<String>): Map<String, RecorderTranscript> =
        synchronized(ready) { ready.filterKeys { it in noteIds } }
}

/** A sync over [radio] with a store in [root]. */
internal fun recorderSync(
    root: File,
    radio: FakeRadio,
    library: FakeLibrary,
    clock: () -> Long = { NOW },
): Pair<RecorderSync, RecorderStore> {
    val store = RecorderStore(File(root, "recorder.json"), Dispatchers.IO)
    val sync = RecorderSync(
        RecorderSync.Parts(
            store = store,
            radio = radio,
            vault = FakeVault(),
            library = library,
            downloads = File(root, "downloads"),
            io = Dispatchers.IO,
            clock = clock,
            zone = { java.time.ZoneOffset.UTC },
            timeouts = TEST_TIMEOUTS,
        ),
    )
    return sync to store
}

/** Binds [DEVICE] in [store] with [key] as if PAIR had run. */
internal suspend fun RecorderStore.bound(key: ByteArray) = update {
    it.copy(binding = RecorderBinding(DEVICE, FakeVault().wrap(key)))
}

/** 2026-11-25 10:11:12 UTC, the time of FakeRecorder's clock. */
internal const val NOW = 1_795_601_472_000L

/** WAV-like bytes: a 44-byte header and [size] - 44 bytes of a pattern. */
internal fun recording(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }
