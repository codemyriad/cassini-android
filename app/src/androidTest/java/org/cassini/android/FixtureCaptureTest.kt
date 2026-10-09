package org.cassini.android

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.Session
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.floats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/**
 * Writes the fixture trees of fixtures/filesdir: the 0.1.0 ones with this build's own code, the 0.0.x
 * ones with the 0.0.4/0.0.6 session and catalogue writers copied below. It is not a check. It runs only
 * with `-e captureFixtures true`, needs the speech, speaker and voiceprint models, and leaves the app's
 * data as it found it. scripts/pull-filesdir-fixture.sh copies the trees from cache/fixture-capture.
 * fixtures/README.md describes every tree.
 */
class FixtureCaptureTest {
    @get:Rule val preserveLibrary = PreserveLibraryRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val files = context.filesDir
    private val output = File(context.cacheDir, "fixture-capture")
    private val inputs = File(context.cacheDir, "fixture-capture-inputs")
    private val sessions = SessionStore(files)
    private val random = Random(SEED)

    @Test fun captureFixtureTrees() {
        assumeTrue(
            "Pass -e captureFixtures true",
            InstrumentationRegistry.getArguments().getString("captureFixtures") == "true"
        )
        assumeTrue("Install the speech model", ModelCatalog.PARAKEET.ready(files))
        assumeTrue("Install the speaker model", ModelCatalog.DIARIZATION.ready(files))
        assumeTrue("Install the voiceprint model", ModelCatalog.VOICEPRINT.ready(files))
        assumeTrue(
            "Run on a device without saved people",
            (context.applicationContext as CassiniApplication).voices.load().isEmpty()
        )
        // The rule restores the catalogue, documents, checkpoints and voices; these two records and the estimate it does not.
        val records = listOf("latest.words.json", "latest.processing.json").associateWith {
            File(files, it).takeIf(File::exists)?.let { file -> file.readBytes() to file.lastModified() }
        }
        val estimates = context.getSharedPreferences("processing-estimates", Context.MODE_PRIVATE)
        val finalization = estimates.all["finalizationMs"] as? Long
        val notesBefore = LibraryStore(files).load().map { it.id }.toSet()
        val documentsBefore = names("documents")
        val printsBefore = names("voiceprints")
        output.deleteRecursively()
        try {
            ActivityScenario.launch(LibraryActivity::class.java).use {
                waitFor("The library marks its one-time migration") { File(files, "library-migrated").exists() }
            }
            val partial = capturePartial()
            val (first, sealed) = captureSealed()
            val words = CassiniDocument.read(sealed).selected(null)!!.transcript!!.words
            val legacy = legacyNote(words)
            val newDocuments = names("documents") - documentsBefore
            val basic = basicTree(notesBefore, newDocuments, names("voiceprints") - printsBefore, partial)
            derivedTrees(basic, legacy, first)
            version004Tree(words)
            version006Tree(words)
        } finally {
            records.forEach { (name, saved) -> restore(File(files, name), saved) }
            restore(estimates, finalization)
            inputs.deleteRecursively()
        }
    }

    private fun restore(file: File, saved: Pair<ByteArray, Long>?) {
        if (saved == null) {
            file.delete()
        } else {
            file.writeBytes(saved.first)
            file.setLastModified(saved.second)
        }
    }

    private fun restore(estimates: android.content.SharedPreferences, finalization: Long?) {
        val editor = estimates.edit()
        if (finalization == null) editor.remove("finalizationMs") else editor.putLong("finalizationMs", finalization)
        editor.commit()
    }

    private fun waitForImport(before: String?) = waitFor("The import becomes the note") {
        sessions.load().uri.let { it != before && it?.endsWith(".input") == true }
    }

    /** The catalogue [library] restricted to the notes [keep] accepts, serialised as LibraryStore writes it. */
    private fun catalogue(library: File, keep: (String) -> Boolean): ByteArray {
        val notes = JSONObject(library.readText()).getJSONArray("notes")
        val kept = (0 until notes.length()).map { notes.getJSONObject(it) }.filter { keep(it.getString("id")) }
        return JSONObject().put("version", 1).put("notes", JSONArray(kept)).toString().toByteArray()
    }

    /**
     * A 60 s import (the clip, then silence), stopped as soon as the first 28 s window is saved, so the checkpoint
     * holds little more than the 2 s overlap. The speaker is named afterwards, from the reopened partial note.
     */
    private fun capturePartial(): String {
        val clip = audioDecoder(context).decode(Uri.fromFile(asset("fleurs-it-short.wav")))
        val padded = FloatArray(PARTIAL_SECONDS * clip.sampleRate).also { clip.samples.copyInto(it) }
        val source = File(inputs, "fleurs-it-padded.m4a").also { encodeAac(PcmAudio(padded, clip.sampleRate), it) }
        val before = sessions.load().uri
        var scenario = open(source)
        val uri: String
        try {
            waitForImport(before)
            uri = sessions.load().uri!!
            transcribe(scenario)
            stopAfterFirstWindow(scenario, File(ProcessingCheckpoint(files, uri).directory, "checkpoint.json"))
            waitFor("Stop keeps a durable partial result") { sessions.load().processingPaused }
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            nameFirstSpeaker(scenario)
            waitFor("The draft name is saved with the checkpoint") {
                ProcessingCheckpoint(files, uri).loadNames().first.values.contains(PERSON)
            }
        } finally { scenario.close() }
        val saved = ProcessingCheckpoint(files, uri).load()
        assertNotNull("The paused job has a checkpoint", saved)
        assertTrue(
            "Stopped after a window and before the end",
            saved!!.doneMs in WINDOW_MS until PARTIAL_SECONDS * 1000L
        )
        return uri
    }

    /** Presses Stop as soon as a checkpoint with a recognised window appears; the job checks the request after each block. */
    private fun stopAfterFirstWindow(scenario: ActivityScenario<MainActivity>, checkpoint: File) {
        var seen = -1L
        var done = false
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!done && SystemClock.uptimeMillis() < deadline) {
            val modified = checkpoint.lastModified()
            if (modified != seen && modified > 0) {
                seen = modified
                done = try {
                    JSONObject(checkpoint.readText()).getLong("doneMs") >= WINDOW_MS
                } catch (_: org.json.JSONException) { false }
            }
            if (!done) SystemClock.sleep(10)
        }
        assertTrue("The first window is recognised", done)
        scenario.onActivity { it.findViewById<Button>(R.id.cancel_operation).performClick() }
    }

    /** The FLEURS clip transcribed to the end, then its speaker named and remembered. Returns the first and the relabelled document. */
    private fun captureSealed(): Pair<File, File> {
        val before = sessions.load().uri
        val scenario = open(asset("fleurs-it-short.m4a"))
        try {
            waitForImport(before)
            transcribe(scenario)
            waitFor("The transcript is sealed into a document") { sessions.load().document != null }
            val sealed = File(sessions.load().document!!)
            // Relabelling replaces this file; the pre-library tree keeps both versions of the meeting.
            val first = File(inputs, sealed.name)
            sealed.copyTo(first, overwrite = true)
            first.setLastModified(sealed.lastModified())
            nameFirstSpeaker(scenario)
            waitFor("The relabelled document replaces the first one and the person is saved") {
                sessions.load().document.let { it != null && it != sealed.absolutePath } && File(files, "voices.json").exists()
            }
            return first to File(sessions.load().document!!)
        } finally { scenario.close() }
    }

    /** A 0.0.x capture: an AAC recording whose transcript lives inline in the catalogue. Written with LibraryStore. */
    private fun legacyNote(words: List<Word>): Session {
        val file = File(files, "documents/${UUID.randomUUID()}.m4a")
        asset("fleurs-it-short.m4a").copyTo(file)
        val session = Session(
            uri = Uri.fromFile(file).toString(),
            name = "Recording.m4a",
            durationMs = CLIP_MS,
            transcript = Transcript(words.map { it.copy(speaker = "spk_1") }, "it"),
            inferenceMs = 4200,
            processingMs = 5100,
            libraryId = UUID.randomUUID().toString()
        )
        return LibraryStore(files).save(session)
    }

    private fun basicTree(notesBefore: Set<String>, documents: Set<String>, prints: Set<String>, partial: String): FixtureTree {
        val tree = FixtureTree(files, output, "v0.1.0-basic")
        val library = File(files, "library.json")
        tree.write("library.json", catalogue(library) { it !in notesBefore }, library.lastModified())
        listOf("library-migrated", "session.json", "latest.words.json", "latest.processing.json").forEach {
            tree.copy(File(files, it))
        }
        documents.forEach { tree.copy(File(files, "documents/$it")) }
        tree.copyAll(ProcessingCheckpoint(files, partial).directory)
        // Fixtures never hold a real voiceprint: each person and print gets the same random unit vector.
        val vectors = mutableMapOf<String, FloatArray>()
        val voices = File(files, "voices.json")
        val people = JSONObject(voices.readText())
        val list = people.getJSONArray("voices")
        (0 until list.length()).map { list.getJSONObject(it) }.forEach {
            it.put("mean", floats(vector(vectors, it.getString("id"), it.getInt("dim"))))
        }
        tree.write("voices.json", people.toString().toByteArray(), voices.lastModified())
        prints.forEach { name ->
            val file = File(files, "voiceprints/$name")
            val json = JSONObject(file.readText())
            val speakers = json.getJSONObject("speakers")
            speakers.keys().forEach { id ->
                val speaker = speakers.getJSONObject(id)
                val owner = speaker.optJSONObject("match")?.getString("voiceId") ?: id
                speaker.put("embedding", floats(vector(vectors, owner, speaker.getJSONArray("embedding").length())))
            }
            tree.write("voiceprints/$name", json.toString().toByteArray(), file.lastModified())
        }
        listOf("parakeet-v3", "vad", "speaker-models").forEach { tree.standIns(File(files, it)) }
        return tree.finish()
    }

    private fun derivedTrees(basic: FixtureTree, legacy: Session, first: File) {
        val library = File(basic.directory, "library.json").readBytes()
        val truncated = library.copyOf(library.size / 2)
        // A library.json that no longer parses, with no .bak to fall back to.
        FixtureTree(files, output, "v0.1.0-corrupt-library").apply {
            copyFrom(basic) { it != "library.json" }
            write("library.json", truncated, basic.time("library.json"))
        }.finish()
        // An intact library.json beside the partial .new of a write that never finished.
        FixtureTree(files, output, "v0.1.0-corrupt-library-new").apply {
            copyFrom(basic) { true }
            write("library.json.new", truncated, basic.time("library.json") + 1000)
        }.finish()
        // Sealed documents only: two versions of one meeting, no catalogue, session or marker.
        FixtureTree(files, output, "v0.1.0-pre-library").apply {
            copyFrom(basic) { it.startsWith("documents/") && it.endsWith(".opus") }
            write("documents/${first.name}", first.readBytes(), first.lastModified())
        }.finish()
        recordingCrashTree(basic, legacy)
    }

    /** What an interrupted capture leaves: a torn live Opus file, two orphan AAC captures and a stale import part. */
    private fun recordingCrashTree(basic: FixtureTree, legacy: Session) {
        val tree = FixtureTree(files, output, "v0.1.0-rec-crash")
        val legacyPath = tree.path(File(Uri.parse(legacy.uri).path!!))
        tree.copyFrom(basic) {
            it == legacyPath || it.startsWith("parakeet-v3/") || it.startsWith("vad/") || it.startsWith("speaker-models/")
        }
        tree.write("library.json", catalogue(File(basic.directory, "library.json")) { it == legacy.libraryId }, now())
        SessionStore(tree.directory).save(legacy)
        tree.recordTime("session.json", now())
        val clip = audioDecoder(context).decode(Uri.fromFile(asset("fleurs-it-short.wav")))
        val live = File(tree.directory, "documents/${UUID.randomUUID()}.rec.opus")
        writeTornCapture(live, clip)
        tree.recordTime(tree.relative(live), now())
        val aac = File(inputs, "capture.m4a").also { encodeAac(clip, it) }
        tree.write("documents/${UUID.randomUUID()}.rec.m4a", aac.readBytes(), now())
        // The writer reserves room for the MP4 index at the start and fills it on stop; a capture cut short
        // has a free box there and no index.
        val unfinished = unindexed(aac.readBytes())
        tree.write("documents/${UUID.randomUUID()}.rec.m4a", unfinished.copyOf(unfinished.size * 3 / 5), now())
        val part = asset("fleurs-it-short.m4a").readBytes()
        tree.write("documents/${UUID.randomUUID()}.part", part.copyOf(PART_BYTES), now() - 2 * DAY_MS)
        tree.finish()
    }

    /** 0.0.4: an AAC capture with its transcript inline in session.json, the FP32 model, no catalogue. */
    private fun version004Tree(words: List<Word>) = FixtureTree(files, output, "v0.0.4-style").apply {
        val capture = "documents/${UUID.randomUUID()}.m4a"
        write(capture, asset("fleurs-it-short.m4a").readBytes(), now() - 3 * DAY_MS)
        val session = recordedSession(
            capture,
            words
        ).copy(name = "Recording.m4a", screen = UUID.randomUUID().toString())
        write("session.json", LegacySession.sessionJson(session).toString().toByteArray(), now() - 3 * DAY_MS)
        fp32Model(this)
    }.finish()

    /** 0.0.6: a WAV capture with an inline transcript, an opened import, both models and the pyannote speaker files. */
    private fun version006Tree(words: List<Word>) = FixtureTree(files, output, "v0.0.6-beta").apply {
        val m4a = asset("fleurs-it-short.m4a").readBytes()
        val capture = "documents/${UUID.randomUUID()}.wav"
        write(capture, asset("fleurs-it-short.wav").readBytes(), now() - 2 * DAY_MS)
        val imported = "documents/${OggOpus.hex(MessageDigest.getInstance("SHA-256").digest(m4a))}.input"
        write(imported, m4a, now() - DAY_MS)
        val recorded = recordedSession(capture, words)
        val opened = LegacySession(
            uri = uri(imported),
            name = "fleurs-it-short.m4a",
            durationMs = CLIP_MS,
            fp32 = true,
            resultPrecision = "",
            libraryId = UUID.randomUUID().toString(),
        )
        val notes = listOf(recorded to now() - 2 * DAY_MS, opened to now() - DAY_MS).map { (session, created) ->
            val text = session.transcript?.words?.joinToString(" ") { it.text }.orEmpty()
            LegacySession.noteJson(session.libraryId!!, created, text, session)
        }
        val library = JSONObject().put("version", 1).put("notes", JSONArray(notes))
        write("library.json", library.toString().toByteArray(), now() - DAY_MS)
        val shown = recorded.copy(screen = UUID.randomUUID().toString())
        write("session.json", LegacySession.sessionJson(shown).toString().toByteArray(), now() - DAY_MS)
        fp32Model(this)
        val installed = now() - 4 * DAY_MS
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").forEach {
            write("parakeet-v3/$it", ByteArray(0), installed)
        }
        write("parakeet-v3/verified", ModelCatalog.PARAKEET_REVISION.toByteArray(), installed)
        write("vad/silero_vad.onnx", ByteArray(0), installed)
        write("vad/verified", ModelCatalog.sileroVad.sha256.toByteArray(), installed)
        write("speaker-models/segmentation.int8.onnx", ByteArray(0), installed)
        write("speaker-models/embedding.onnx", ByteArray(0), installed)
        write("speaker-models/verified", "$PYANNOTE_SHA256\n$ERES2NET_SHA256".toByteArray(), installed)
    }.finish()

    /** A 0.0.x microphone note transcribed with the FP32 model; its transcript stays inline. */
    private fun recordedSession(capture: String, words: List<Word>) = LegacySession(
        uri = uri(capture),
        name = "Recording.wav",
        transcript = Transcript(words.map { it.copy(speaker = "spk_1") }, "it"),
        durationMs = CLIP_MS,
        inferenceMs = 9800,
        fp32 = true,
        resultPrecision = "FP32",
        processingMs = 11200,
        libraryId = UUID.randomUUID().toString(),
    )

    private fun fp32Model(tree: FixtureTree) {
        val installed = now() - 4 * DAY_MS
        listOf("encoder.onnx", "encoder.weights", "decoder.onnx", "joiner.onnx", "tokens.txt").forEach {
            tree.write("parakeet-v3-fp32/$it", ByteArray(0), installed)
        }
        tree.write("parakeet-v3-fp32/verified", FP32_REVISION.toByteArray(), installed)
    }

    private fun uri(path: String) = Uri.fromFile(File(files, path)).toString()

    private fun vector(vectors: MutableMap<String, FloatArray>, owner: String, size: Int) = vectors.getOrPut(owner) {
        val values = FloatArray(size) { random.nextGaussian().toFloat() }
        val norm = kotlin.math.sqrt(values.sumOf { it.toDouble() * it }).toFloat()
        FloatArray(size) { values[it] / norm }
    }

    private fun names(directory: String) = File(files, directory).listFiles().orEmpty().map { it.name }.toSet()

    private fun now() = System.currentTimeMillis()

    private fun asset(name: String): File = File(inputs.also { it.mkdirs() }, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun open(file: File): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Intent(Intent.ACTION_VIEW, Uri.fromFile(file), context, MainActivity::class.java))

    private fun transcribe(scenario: ActivityScenario<MainActivity>) {
        waitFor("Transcribe is ready") {
            var ready = false
            scenario.onActivity { ready = it.findViewById<Button>(R.id.transcribe_button).isEnabled }
            ready
        }
        scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
    }

    /** Opens the naming dialog of "Speaker 1" as soon as it appears, names the speaker and keeps "remember" checked. */
    private fun nameFirstSpeaker(scenario: ActivityScenario<MainActivity>) {
        var opened = false
        waitFor("The first speaker appears") {
            scenario.onActivity { activity ->
                val view = activity.findViewById<TextView>(R.id.transcript_text)
                val text = view.text as? Spanned
                val label = activity.getString(R.string.speaker_label, 1)
                val at = text?.toString()?.indexOf(label) ?: -1
                if (!opened && at >= 0) {
                    text!!.getSpans(at, at + label.length, ClickableSpan::class.java).firstOrNull()?.let {
                        opened = true
                        it.onClick(view)
                    }
                }
            }
            opened
        }
        onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).perform(replaceText(PERSON))
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }

    private companion object {
        const val SEED = 20261009L
        const val PERSON = "Ada Example"
        const val PARTIAL_SECONDS = 60
        const val WINDOW_MS = 28_000L
        const val CLIP_MS = 15_840L
        const val TIMEOUT_MS = 240_000L
        const val DAY_MS = 86_400_000L
        const val PART_BYTES = 32 * 1024
        const val FP32_REVISION = "1a468a35cbba69418f126de829e75261dea4a4e4"
        const val PYANNOTE_SHA256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"
        const val ERES2NET_SHA256 = "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b"
    }
}

/** One tree under cache/fixture-capture/<name>, plus the .mtimes manifest of the modification times git does not keep. */
private class FixtureTree(private val files: File, root: File, name: String) {
    val directory = File(root, name).also { check(it.mkdirs()) { "Could not create $it" } }
    private val times = sortedMapOf<String, Long>()

    fun path(file: File) = file.relativeTo(files).invariantSeparatorsPath
    fun relative(file: File) = file.relativeTo(directory).invariantSeparatorsPath
    fun time(path: String) = times.getValue(path)
    fun recordTime(path: String, mtime: Long) { times[path] = mtime }

    fun copy(file: File) = write(path(file), file.readBytes(), file.lastModified())
    fun copyAll(directory: File) = directory.walkTopDown().filter { it.isFile }.forEach(::copy)
    fun copyFrom(other: FixtureTree, include: (String) -> Boolean) =
        other.times.filterKeys(
            include
        ).forEach { (path, mtime) -> write(path, File(other.directory, path).readBytes(), mtime) }

    /** Model blobs become zero-length files with the same names; the verified markers are kept. */
    fun standIns(models: File) = models.listFiles().orEmpty().filter { it.isFile }.forEach {
        if (it.name.startsWith("verified")) copy(it) else write(path(it), ByteArray(0), it.lastModified())
    }

    fun write(path: String, bytes: ByteArray, mtime: Long) {
        val target = File(directory, path)
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
        times[path] = mtime
    }

    fun finish(): FixtureTree {
        File(directory, ".mtimes").writeText(times.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        return this
    }
}

/** The session of 0.0.4 to 0.0.6, with the model choice that 0.1.0 dropped. */
private data class LegacySession(
    val uri: String? = null,
    val name: String = "",
    val transcript: Transcript? = null,
    val durationMs: Long = 0,
    val inferenceMs: Long = 0,
    val positionMs: Int = 0,
    val fp32: Boolean = false,
    val resultPrecision: String = "INT8",
    val document: String? = null,
    val selectedVariant: String? = null,
    val modelChoice: String = if (fp32) "fp32" else "int8",
    val processingMs: Long = 0,
    val libraryId: String? = null,
    val screen: String? = null,
) {
    companion object {
        /** SessionStore.save of v0.0.4-beta and v0.0.6-beta. */
        fun sessionJson(
            session: LegacySession
        ): JSONObject = JSONObject().put("uri", session.uri ?: "").put("name", session.name)
            .put("transcript", session.transcript?.takeIf { session.document == null }?.let { JSONObject(it.json()) })
            .put("durationMs", session.durationMs).put("inferenceMs", session.inferenceMs)
            .put("positionMs", session.positionMs).put("fp32", session.fp32)
            .put("resultPrecision", session.resultPrecision)
            .put("document", session.document ?: "").put("selectedVariant", session.selectedVariant ?: "")
            .put("modelChoice", session.modelChoice).put("processingMs", session.processingMs)
            .put("libraryId", session.libraryId ?: "").put("screen", session.screen ?: "")

        /** LibraryNote.json of v0.0.4-beta and v0.0.6-beta. */
        fun noteJson(id: String, createdAt: Long, text: String, session: LegacySession): JSONObject =
            JSONObject().put("id", id).put("createdAt", createdAt).put("text", text)
                .put(
                    "session",
                    JSONObject().put("uri", session.uri).put("name", session.name)
                        .put("document", session.document).put("selectedVariant", session.selectedVariant)
                        .put(
                            "transcript",
                            session.transcript?.takeIf { session.document == null }?.let { JSONObject(it.json()) }
                        )
                        .put("durationMs", session.durationMs).put("positionMs", session.positionMs)
                        .put("inferenceMs", session.inferenceMs).put("processingMs", session.processingMs)
                        .put("resultPrecision", session.resultPrecision)
                )
    }
}

/** AAC-LC in MP4, mono, 32 kb/s: the MediaRecorder capture format of API 26-28. */
private fun encodeAac(audio: PcmAudio, file: File) {
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 32000)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val pcm = shorts(audio.samples)
        val sink = Mp4Sink(muxer)
        var cursor = 0
        while (!sink.ended) {
            if (cursor <= pcm.size) cursor = queue(codec, pcm, cursor, audio.sampleRate)
            sink.drain(codec)
        }
        codec.stop()
        muxer.stop()
    } finally {
        codec.release()
        muxer.release()
    }
}

/** Writes each encoded AAC frame into the MP4 track, which starts with the encoder's output format. */
private class Mp4Sink(private val muxer: MediaMuxer) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    var ended = false
        private set

    fun drain(codec: MediaCodec) {
        val output = codec.dequeueOutputBuffer(info, 10_000)
        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            track = muxer.addTrack(codec.outputFormat)
            muxer.start()
        } else if (output >= 0) {
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (info.size > 0 && !config) muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(output, false)
        }
    }
}

/** [mp4] with its top-level moov box turned into a free box of the same size. */
private fun unindexed(mp4: ByteArray): ByteArray {
    val copy = mp4.copyOf()
    var offset = 0
    while (offset + 8 <= copy.size) {
        val size = java.nio.ByteBuffer.wrap(copy, offset, 4).int
        if (String(copy, offset + 4, 4, Charsets.US_ASCII) == "moov") {
            "free".toByteArray().copyInto(copy, offset + 4)
            return copy
        }
        check(size >= 8) { "Unexpected MP4 box size $size" }
        offset += size
    }
    error("No moov box")
}

private fun shorts(samples: FloatArray) = ShortArray(
    samples.size
) { (samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }

/**
 * Queues the samples from [cursor] that fit one input buffer, if one is free, and returns the new cursor.
 * Without a [frame] size, the call after the last sample queues end of stream and returns past the end.
 */
private fun queue(codec: MediaCodec, pcm: ShortArray, cursor: Int, rate: Int, frame: Int? = null): Int {
    val index = codec.dequeueInputBuffer(10_000)
    if (index < 0) return cursor
    val buffer = codec.getInputBuffer(index)!!
    buffer.clear()
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val n = minOf(buffer.remaining() / 2, pcm.size - cursor, frame ?: Int.MAX_VALUE)
    for (i in 0 until n) buffer.putShort(pcm[cursor + i])
    val end = n == 0 && frame == null
    codec.queueInputBuffer(
        index,
        0,
        n * 2,
        cursor * 1_000_000L / rate,
        if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
    )
    return if (end) pcm.size + 1 else cursor + n
}

/**
 * A `.rec.opus` as OpusRecording writes it on API 29+ (platform Opus encoder at 48 kHz, OggOpus.LiveMuxer),
 * fed with [clip] instead of the microphone, and cut inside its last page as a killed process leaves it.
 */
private fun writeTornCapture(file: File, clip: PcmAudio) {
    val rate = 48000
    val frame = rate / 50
    // Sample-and-hold to 48 kHz is enough for a container check.
    val size = (clip.samples.size.toLong() * rate / clip.sampleRate).toInt()
    val pcm = shorts(FloatArray(size) { clip.samples[(it.toLong() * clip.sampleRate / rate).toInt()] })
    val codec = MediaCodec.createEncoderByType("audio/opus")
    try {
        val format = MediaFormat.createAudioFormat("audio/opus", rate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 48000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame * 2)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        FileOutputStream(file).use { output ->
            val stream = output.buffered(1 shl 16)
            val sink = OpusSink(stream)
            var cursor = 0
            while (cursor + frame <= pcm.size) {
                cursor = queue(codec, pcm, cursor, rate, frame)
                sink.drain(codec)
            }
            // No end of stream and no final page: the process died here.
            stream.flush()
            output.fd.sync()
        }
    } finally { codec.release() }
    RandomAccessFile(file, "rw").use { it.setLength(it.length() - TORN_BYTES) }
}

private const val TORN_BYTES = 37L

/** Moves encoded packets into an Ogg stream that starts at the encoder's OpusHead, like OpusRecording.drain. */
private class OpusSink(private val stream: OutputStream) {
    private var muxer: OggOpus.LiveMuxer? = null

    fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var index = codec.dequeueOutputBuffer(info, 0)
        while (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer(
                    "csd-0"
                )?.let { config(ByteArray(it.remaining()).also { bytes -> it.get(bytes) }) }
            } else {
                take(codec, index, info)
            }
            index = codec.dequeueOutputBuffer(info, 0)
        }
    }

    private fun take(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(index)!!
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size).also { buffer.get(it) }
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config(
                bytes
            )
        } else if (bytes.isNotEmpty()) muxer!!.add(bytes)
        codec.releaseOutputBuffer(index, false)
    }

    private fun config(bytes: ByteArray) {
        if (muxer != null) return
        val marker = "OpusHead".toByteArray()
        val start = (0..(bytes.size - 19)).firstOrNull { p -> marker.indices.all { bytes[p + it] == marker[it] } } ?: return
        muxer = OggOpus.LiveMuxer(stream, bytes.copyOfRange(start, start + 19), CassiniDocument.tagsPacket(emptyList()))
    }
}
