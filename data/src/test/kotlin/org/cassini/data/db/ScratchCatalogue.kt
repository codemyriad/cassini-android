package org.cassini.data.db

import androidx.test.core.app.ApplicationProvider
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.Origin
import org.cassini.data.AppPaths
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/** A catalogue under a scratch [AppPaths] root, opened off the Robolectric main thread as the app does. */
class ScratchCatalogue(folder: TemporaryFolder) {
    val paths = AppPaths(root = folder.newFolder("root"), cache = folder.newFolder("cache"))
    var clock = 1_000L

    fun open(): OpenedDatabase = offMain {
        CassiniDatabaseOpener(ApplicationProvider.getApplicationContext(), paths) { clock }.open()
    }
}

/** Runs [block] on a worker thread: Robolectric runs the test itself on the main thread. */
fun <T> offMain(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor()
    try {
        return executor.submit<T> { block() }.get()
    } finally {
        executor.shutdown()
    }
}

fun note(
    id: String,
    title: String = "Note $id",
    createdAt: Long = 1_000,
    audioKind: AudioKind = AudioKind.DOCUMENT,
): NoteEntity = NoteEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    addedAt = createdAt,
    origin = Origin.RECORDED,
    audioFile = if (audioKind == AudioKind.DOCUMENT) "document.opus" else "capture.opus",
    audioKind = audioKind,
    durationMs = 60_000,
    documentState = if (audioKind == AudioKind.DOCUMENT) DocumentState.OK else null,
    updatedAt = createdAt,
)

/** Words "w0 w1 ..." from [text], alternating speakers every [turn] words. */
fun words(text: String, turn: Int = 3): List<Word> = text.split(' ').mapIndexed { index, word ->
    val speaker = if ((index / turn) % 2 == 0) "S1" else "S2"
    Word(speaker, startMs = index * 500L, endMs = index * 500L + 400, text = word)
}

/** The cache rows of [note] with one variant per entry of [variants] (id to words); the first one is the default. */
fun cache(
    note: NoteEntity,
    variants: List<Pair<String, List<Word>>>,
    selected: String = variants.first().first,
): NoteCache {
    val variantRows = variants.mapIndexed { ordinal, (id, words) ->
        VariantEntity(
            noteId = note.id,
            variantId = id,
            ordinal = ordinal,
            isDefault = ordinal == 0,
            wordCount = words.size,
            speakerCount = words.map { it.speaker }.distinct().size,
        )
    }
    val speakerRows = variants.flatMap { (id, words) ->
        words.map { it.speaker }.distinct().mapIndexed { ordinal, speaker ->
            val number = ordinal + 1
            SpeakerEntity(note.id, id, speaker, ordinal, "Speaker $number", true, placeholderNumber = number)
        }
    }
    val wordRows = variants.flatMap { (id, words) ->
        words.mapIndexed { index, word ->
            WordEntity(note.id, id, index, word.startMs, word.endMs, word.speaker, word.text)
        }
    }
    val body = variants.first { it.first == selected }.second.joinToString(" ") { it.text }
    return NoteCache(note.copy(selectedVariant = selected), variantRows, speakerRows, wordRows, body)
}

/** A one-column query on the open database, as strings. */
fun CassiniDatabase.column(sql: String, vararg args: Any?): List<String?> =
    openHelper.writableDatabase.query(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(if (cursor.isNull(0)) null else cursor.getString(0)) }
    }

fun File.writeGarbage() {
    parentFile?.mkdirs()
    writeBytes(ByteArray(8192) { (it * 31 + 7).toByte() })
}
