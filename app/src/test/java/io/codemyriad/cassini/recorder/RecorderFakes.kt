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
import java.util.concurrent.CountDownLatch

// The fakes of the recorder sync tests: a radio over FakeRecorder (the recorder's XREC state machine behind pipes), a
// vault without the Keystore and a library that records what the sync asks for.

internal val DEVICE = RecorderDevice("00:11:22:33:44:55", "Cassini_55")

/** Short waits: the fake recorder answers at once, and a lost frame should cost little test time. */
internal val TEST_TIMEOUTS = XrecTimeouts(replyMillis = 2_000, frameMillis = 1_000, quietMillis = 50, drainMillis = 500)

/**
 * A radio whose recorder is [device]; each [open] after the first is a new link to the same recorder. With [stall], the
 * link accepts no byte: a write blocks until the link closes, as a socket whose peer grants no credits.
 */
internal class FakeRadio(var device: FakeRecorder) : RecorderRadio {
    @Volatile var readiness = RadioReadiness.READY

    @Volatile var reachable = true

    @Volatile var bond = BondResult.BONDED

    @Volatile var stall = false
    var opens = 0
        private set

    override fun readiness(): RadioReadiness = readiness

    override suspend fun bond(address: String): BondResult = bond

    override suspend fun open(address: String): RecorderLink {
        if (!reachable) throw IOException("No answer")
        if (stall) return StalledLink()
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

    private class StalledLink : RecorderLink {
        private val closed = CountDownLatch(1)
        override val input = XrecInput { _, _, _, _ -> if (closed.count == 0L) -1 else 0 }
        override val output = object : OutputStream() {
            override fun write(b: Int) {
                // Ignores interrupts, as a socket write does; only the close ends it.
                while (closed.count > 0) {
                    try {
                        closed.await()
                    } catch (_: InterruptedException) {
                        // A socket write does not end on an interrupt.
                    }
                }
                throw IOException("Socket closed")
            }
        }

        override fun close() = closed.countDown()
    }
}

/** "Wraps" a key as hex with a prefix; anything else does not unwrap, as a lost Keystore key. */
internal class FakeVault : KeyVault {
    @Volatile private var generation = 0

    fun invalidate() {
        generation++
    }

    override fun clear() = invalidate()

    override fun wrap(key: ByteArray): String = "$PREFIX$generation:${XrecAuth.toHex(key)}"

    override fun unwrap(wrapped: String): ByteArray {
        val currentPrefix = "$PREFIX$generation:"
        if (!wrapped.startsWith(currentPrefix)) throw GeneralSecurityException("Not a wrapped key")
        return XrecAuth.fromHex(wrapped.removePrefix(currentPrefix))
    }

    companion object {
        const val PREFIX = "wrapped:"
    }
}

/** A library that keeps the imported bytes; the same bytes again are an existing note. */
internal class FakeLibrary : RecorderLibrary {
    val imported: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
    val titles: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val startedAtByNote: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val transcribed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ready: MutableMap<String, RecorderTranscript> = Collections.synchronizedMap(LinkedHashMap())

    @Volatile var reject: String? = null

    /** The next transcription request fails, as if the process ended after the import. */
    @Volatile var failNextTranscribe = false

    /** Each read of the transcripts fails, as a broken library would. */
    @Volatile var failTranscripts = false

    /** Simulated `QUEUED` / `RUNNING` transcription count reported to `+XREC:SYNC,<pending>`. */
    @Volatile var activeCount = 0

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

    override suspend fun setRecordingStartIfAbsent(noteId: String, startedAt: String) {
        synchronized(startedAtByNote) { startedAtByNote.putIfAbsent(noteId, startedAt) }
    }

    override suspend fun transcribe(noteId: String) {
        if (failNextTranscribe) {
            failNextTranscribe = false
            error("The transcription request failed")
        }
        transcribed += noteId
    }

    override suspend fun needsTranscription(noteId: String): Boolean = noteId !in transcribed && noteId !in ready

    override suspend fun transcripts(noteIds: Collection<String>): Map<String, RecorderTranscript> {
        check(!failTranscripts) { "The library cannot be read" }
        return synchronized(ready) { ready.filterKeys { it in noteIds } }
    }

    override suspend fun activeTranscriptions(noteIds: Collection<String>): Int =
        if (noteIds.isEmpty()) 0 else activeCount
}

/** A sync over [radio] with a store in [root]. */
@Suppress("LongParameterList") // test factory with defaults for each collaborator
internal fun recorderSync(
    root: File,
    radio: FakeRadio,
    library: RecorderLibrary,
    clock: () -> Long = { NOW },
    stallMillis: Long = RecorderSync.DEFAULT_STALL_MS,
    vault: KeyVault = FakeVault(),
): Pair<RecorderSync, RecorderStore> {
    val store = RecorderStore(File(root, "recorder.json"), Dispatchers.IO)
    val sync = RecorderSync(
        RecorderSync.Parts(
            store = store,
            radio = radio,
            vault = vault,
            library = library,
            downloads = File(root, "downloads"),
            io = Dispatchers.IO,
            clock = clock,
            zone = { java.time.ZoneOffset.UTC },
            timeouts = TEST_TIMEOUTS,
            stallMillis = stallMillis,
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
