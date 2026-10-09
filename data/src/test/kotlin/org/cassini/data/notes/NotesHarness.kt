package org.cassini.data.notes

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.Origin
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteLocks
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.ScratchCatalogue
import org.json.JSONObject
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** The committed FLEURS clip (fixtures/audio), an Ogg Opus stream of a few seconds. */
val fixtureOpus: File =
    File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")

/** A synthetic transcript: [text] split into words of 400 ms, a new speaker every [turn] words (S1, S2, S1, …). */
fun transcript(text: String, turn: Int = 3, speakers: List<String> = listOf("S1", "S2")): Transcript =
    Transcript(
        text.split(' ').mapIndexed { index, word ->
            Word(speakers[(index / turn) % speakers.size], index * 500L, index * 500L + 400, word)
        },
    )

/** A Cassini document over the FLEURS clip with [transcript] and [title], built with `CassiniDocument.create`. */
fun documentBytes(transcript: Transcript, title: String): ByteArray =
    CassiniDocument.create(fixtureOpus.readBytes(), transcript, title, JSONObject().put("engine", "test"))

/** The production reader, counting full reads. */
class CountingReader : DocumentReader {
    val reads = AtomicInteger()

    override fun read(file: File): CassiniDocument {
        reads.incrementAndGet()
        return DocumentReader.Verified.read(file)
    }
}

/** Records the queue and purge calls the note repository makes, in order. */
class RecordingPorts : JobQueueWriter, NoteFilesCleaner {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var onDeleteForNote: (String) -> Unit = {}

    override suspend fun requeueAtBack(noteId: String, kind: JobKind) {
        calls += "requeue $noteId $kind"
    }

    override suspend fun cancelAndRemove(noteId: String) {
        calls += "cancel $noteId"
    }

    override suspend fun deleteForNote(noteId: String) {
        calls += "voices $noteId"
        onDeleteForNote(noteId)
    }
}

/**
 * The note and document repositories over a scratch root. [restart] simulates process death: new repositories (an
 * empty document LRU) over the same files and database.
 */
class NotesHarness(folder: TemporaryFolder) {
    private val catalogue = ScratchCatalogue(folder)
    val paths = catalogue.paths
    val db: CassiniDatabase = catalogue.open().database
    val dispatchers =
        CassiniDispatchers(io = Dispatchers.IO, default = Dispatchers.Default, main = Dispatchers.Unconfined)
    val locks = NoteLocks()
    val reader = CountingReader()
    val ports = RecordingPorts()
    val background = CoroutineScope(SupervisorJob())
    var now = 1_700_000_000_000L
    lateinit var documents: DocumentRepository
        private set
    lateinit var notes: NoteRepository
        private set

    init {
        restart()
    }

    fun restart() {
        documents = DocumentRepository(
            db,
            paths,
            dispatchers,
            locks,
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            reader,
        ) { now }
        notes = NoteRepository(db, paths, dispatchers, locks, documents, background, ports, ports) { now }
    }

    /** Waits for the background title writes that renames started. */
    suspend fun awaitBackground() {
        background.coroutineContext.job.children.forEach { it.join() }
    }

    fun close() {
        background.cancel()
        db.close()
    }

    fun dir(id: String): File = paths.note(id)

    fun document(id: String): File = File(dir(id), NoteFiles.DOCUMENT)

    /** A DOCUMENT note whose file is written but whose cache was never derived (as after an import). */
    suspend fun documentNote(id: String, transcript: Transcript, title: String = "Riunione"): NoteEntity {
        dir(id).mkdirs()
        document(id).writeBytes(documentBytes(transcript, title))
        val row = NoteEntity(
            id = id,
            title = title,
            createdAt = now,
            addedAt = now,
            origin = Origin.IMPORTED_DOCUMENT,
            audioFile = NoteFiles.DOCUMENT,
            audioKind = AudioKind.DOCUMENT,
            updatedAt = now,
        )
        db.notes().insert(row)
        return row
    }

    /** A PLAIN note whose audio is a copy of the FLEURS clip named [audioFile]. */
    suspend fun plainNote(id: String, title: String = "Registrazione", audioFile: String = "capture.opus"): NoteEntity {
        dir(id).mkdirs()
        fixtureOpus.copyTo(File(dir(id), audioFile))
        val row = NoteEntity(
            id = id,
            title = title,
            createdAt = now,
            addedAt = now,
            origin = Origin.RECORDED,
            audioFile = audioFile,
            audioKind = AudioKind.PLAIN,
            updatedAt = now,
        )
        db.notes().insert(row)
        return row
    }

    suspend fun row(id: String): NoteEntity = requireNotNull(db.notes().note(id)) { "no row $id" }

    fun readFile(id: String): CassiniDocument = CassiniDocument.read(document(id))

    fun isOk(id: String): Boolean = readFile(id).documentState == DocumentState.OK
}
