package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.XrecInput
import java.io.Closeable
import java.io.File
import java.io.OutputStream

// The seams of the recorder sync (docs/recorder-sync.md). RecorderSync uses only these ports, so its tests run a
// FakeRecorder over pipes and fake library calls; AppGraph passes the Bluetooth, Keystore and library adapters.

/** One open SPP link to a recorder. [close] ends it; a blocked read then ends too. */
interface RecorderLink : Closeable {
    val input: XrecInput
    val output: OutputStream
}

/** Whether the phone can use Bluetooth for the recorder now. */
enum class RadioReadiness {
    READY,

    /** The phone has no Bluetooth. */
    NO_BLUETOOTH,

    /** The user did not grant "Nearby devices" (BLUETOOTH_CONNECT, Android 12 and later). */
    NO_PERMISSION,

    /** Bluetooth is off. */
    OFF,
}

/** How [RecorderRadio.bond] ended. */
enum class BondResult {
    /** The phone had a bond already. The recorder can have lost its side of it (a reset or new firmware). */
    BONDED_BEFORE,

    /** A new bond. */
    BONDED,

    /** No bond came in time, or the user or the recorder refused. */
    FAILED,
}

/** Bluetooth, as the recorder sync needs it. */
interface RecorderRadio {
    fun readiness(): RadioReadiness

    /**
     * Bonds with [address] when the phone has no bond with it yet: Android shows its pairing dialog, and the recorder
     * accepts inside its pairing window.
     */
    suspend fun bond(address: String): BondResult

    /**
     * Opens the secure RFCOMM link to the recorder's SPP service. Throws an [java.io.IOException] when the recorder
     * does not answer: its radio is on only while its user has it in Bluetooth phone mode. Cancellation stops the
     * attempt.
     */
    suspend fun open(address: String): RecorderLink
}

/** Protects the binding key at rest (the Android Keystore in the app). */
interface KeyVault {
    /** The encrypted form of [key], to store. */
    fun wrap(key: ByteArray): String

    /** The key of [wrapped]; throws [java.security.GeneralSecurityException] when this phone cannot decrypt it. */
    fun unwrap(wrapped: String): ByteArray

    /** Deletes the wrapping key when no recorder is bound any more. */
    fun clear() = Unit
}

/** What an import of a downloaded recording gave. */
sealed interface LibraryImport {
    data class Created(val noteId: String) : LibraryImport

    /** The library has a note with the same bytes already. */
    data class Existing(val noteId: String) : LibraryImport

    /** The library refused the file; [failure] is the `Failure` name (for example WAV for a non-PCM WAV). */
    data class Rejected(val failure: String) : LibraryImport
}

/** The finished transcript of a note, ready for the recorder: its [variantId] and the sidecar content. */
data class RecorderTranscript(val variantId: String, val cwx: CwxTranscript)

/** The library, as the recorder sync needs it. */
interface RecorderLibrary {
    /** Imports [file] as a new note titled after [displayName]. */
    suspend fun import(file: File, displayName: String): LibraryImport

    /** Asks for a transcription of [noteId]; without model consent the note waits for it ("Needs model"). */
    suspend fun transcribe(noteId: String)

    /**
     * True when the live note [noteId] has no transcript and no transcription job: an import that found the note
     * already (the sync stopped between the import and the transcription request) asks for it then.
     */
    suspend fun needsTranscription(noteId: String): Boolean

    /** The finished transcripts of those of [noteIds] that have one now. */
    suspend fun transcripts(noteIds: Collection<String>): Map<String, RecorderTranscript>
}
