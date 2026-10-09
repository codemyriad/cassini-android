package org.cassini.data

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.Origin
import org.cassini.data.db.CassiniDatabaseOpener
import org.cassini.data.db.NoteCache
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.VariantEntity
import org.cassini.data.db.WordEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Measures the parsed-transcript cache for a one-hour transcript (about 9 500 words): loading the `words` rows from
 * Room (all of them, and the first screen) against parsing the `cassini.words.v1` JSON body, which is what an open
 * without a cache costs before the audio digest. Prints the times as instrumentation status and to logcat
 * (tag WordCacheDeviceTest). The only assertion is that the cache returns every word in order.
 */
@RunWith(AndroidJUnit4::class)
class WordCacheDeviceTest {
    private lateinit var root: File
    private lateinit var paths: AppPaths
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        root = File(context.filesDir, "WordCacheDeviceTest-${System.nanoTime()}")
        paths = AppPaths(root = File(root, "files"), cache = File(root, "cache"))
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun oneHour(): List<Word> {
        val vocabulary = listOf("quindi", "la", "consegna", "slitta", "a", "giovedì,", "but", "the", "price", "stays.")
        return List(WORDS) { index ->
            Word("S${(index / 25) % 4}", index * 380L, index * 380L + 300, vocabulary[index % vocabulary.size])
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1e6
    }

    @Test fun measureLoadingAOneHourTranscript() = runBlocking {
        val words = oneHour()
        val note = NoteEntity(
            id = "n1",
            title = "Long",
            createdAt = 1,
            addedAt = 1,
            origin = Origin.RECORDED,
            audioFile = "document.opus",
            audioKind = AudioKind.DOCUMENT,
            updatedAt = 1,
        )
        val rows = words.mapIndexed { i, w -> WordEntity("n1", "v1", i, w.startMs, w.endMs, w.speaker, w.text) }
        val variant = VariantEntity("n1", "v1", ordinal = 0, isDefault = true, wordCount = words.size)
        val write = CassiniDatabaseOpener(context, paths).open().database.let { db ->
            val (_, ms) = timed {
                runBlocking { db.cache().replaceCache(NoteCache(note, listOf(variant), emptyList(), rows, body = "")) }
            }
            db.close()
            ms
        }
        // A fresh open, as after process death.
        val db = CassiniDatabaseOpener(context, paths).open().database
        val (firstScreen, firstScreenMs) = timed { runBlocking { db.cache().words("n1", "v1", limit = FIRST_SCREEN) } }
        val (all, allMs) = timed { runBlocking { db.cache().words("n1", "v1") } }
        db.close()
        val json = Transcript(words).json()
        val (parsed, jsonMs) = timed { Transcript.fromJson(json) }
        val file = File(root, "words.bin")
        val (_, binWriteMs) = timed { writeBinary(file, words) }
        val (binary, binReadMs) = timed { readBinary(file) }
        assertEquals(words, binary)

        assertEquals(FIRST_SCREEN, firstScreen.size)
        assertEquals(words.map { it.text }, all.map { it.text })
        assertEquals(words.size, parsed.words.size)
        val report = listOf(
            "words=${words.size}",
            "writeMs=%.1f".format(write),
            "firstScreenMs=%.1f".format(firstScreenMs),
            "allRowsMs=%.1f".format(allMs),
            "jsonParseMs=%.1f".format(jsonMs),
            "jsonBytes=${json.length}",
            "binaryWriteMs=%.1f".format(binWriteMs),
            "binaryReadMs=%.1f".format(binReadMs),
            "binaryBytes=${file.length()}",
        ).joinToString(" ")
        Log.i(TAG, report)
        val status = Bundle().apply { putString("stream", "$TAG: $report\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }

    private fun writeBinary(file: File, words: List<Word>) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(words.size)
            for (word in words) {
                out.writeLong(word.startMs)
                out.writeLong(word.endMs)
                out.writeUTF(word.speaker)
                out.writeUTF(word.text)
            }
        }
    }

    private fun readBinary(file: File): List<Word> =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            List(input.readInt()) {
                val start = input.readLong()
                val end = input.readLong()
                Word(input.readUTF(), start, end, input.readUTF())
            }
        }

    private companion object {
        const val TAG = "WordCacheDeviceTest"
        const val WORDS = 9_500
        const val FIRST_SCREEN = 200
    }
}
