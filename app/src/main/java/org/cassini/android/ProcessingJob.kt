package org.cassini.android

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One background transcription (and optionally speaker identification) of a library note. Only the
 * fields that survive a process are persisted; [words] are the live interim transcript.
 */
data class ProcessingJob(
    val noteId: String,
    val name: String = "",
    val speakers: Boolean = false,
    val phase: Phase = Phase.QUEUED,
    val doneMs: Long = 0,
    val totalMs: Long = 0,
    /** Wall time spent on this job, summed across resumed runs. */
    val elapsedMs: Long = 0,
    /** Audio of the current stage processed in [stageMs]: what the realtime multiple is measured on. */
    val audioMs: Long = 0,
    /** Wall time the current stage's [audioMs] took; decoding included for transcription. */
    val stageMs: Long = 0,
    val failure: Failure? = null,
    /** Words settled so far; the note keeps them as a partial transcript if the job stops. */
    val settledWords: Int = 0,
    /** Runs started for this job; a job that keeps killing the process is not resumed forever. */
    val attempts: Int = 0,
    val words: List<Word> = emptyList(),
) {
    enum class Phase(val active: Boolean) {
        QUEUED(true), DECODE(true), ASR(true), DIARIZE(true), PACKAGE(true),
        DONE(false), FAILED(false), CANCELLED(false), PAUSED(false);
    }

    val percent: Int get() = if (totalMs > 0) (doneMs * 100 / totalMs).toInt().coerceIn(0, 100) else 0
    /** Realtime multiple: 10.0 means ten minutes of audio per minute. */
    val speed: Double? get() = ProcessingSpeed.realtime(audioMs, stageMs)
    val remainingMs: Long? get() = speed?.let { s -> ((totalMs - doneMs).coerceAtLeast(0) / s).toLong() }

    fun json(): JSONObject = JSONObject().put("v", 1).put("noteId", noteId).put("name", name).put("speakers", speakers)
        .put("phase", phase.name).put("doneMs", doneMs).put("totalMs", totalMs).put("elapsedMs", elapsedMs)
        .put("audioMs", audioMs).put("stageMs", stageMs).put("failure", failure?.name ?: "").put("settledWords", settledWords).put("attempts", attempts)

    companion object {
        fun fromJson(json: JSONObject): ProcessingJob? = if (json.optInt("v") != 1) null else ProcessingJob(
            noteId = json.getString("noteId"), name = json.optString("name"), speakers = json.optBoolean("speakers"),
            phase = Phase.valueOf(json.getString("phase")), doneMs = json.optLong("doneMs"), totalMs = json.optLong("totalMs"),
            elapsedMs = json.optLong("elapsedMs"), audioMs = json.optLong("audioMs"), stageMs = json.optLong("stageMs"),
            failure = json.optString("failure").takeIf { it.isNotEmpty() }?.let { name -> Failure.entries.firstOrNull { it.name == name } },
            settledWords = json.optInt("settledWords"), attempts = json.optInt("attempts"))
    }
}

/**
 * The single job this app runs at a time, shared by the service that runs it and the screens that
 * show it. Listeners are called on the main thread. The job survives the process in `job.json`, so
 * an active job found there at start was cut short and can be resumed.
 */
object ProcessingJobs {
    fun interface Listener { fun onJobChanged(job: ProcessingJob?) }

    @Volatile var current: ProcessingJob? = null; private set
    /** True while this process runs [current]; a persisted active job without it was interrupted. */
    @Volatile var running = false; internal set
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var loaded = false

    fun directory(filesDir: File) = File(filesDir, "processing")
    private fun file(filesDir: File) = AtomicFile(File(directory(filesDir), "job.json"))

    /** Reads the persisted job once per process. */
    @Synchronized fun load(filesDir: File): ProcessingJob? {
        if (!loaded) {
            loaded = true
            current = try {
                ProcessingJob.fromJson(JSONObject(file(filesDir).openRead().bufferedReader().use { it.readText() }))
            } catch (_: Exception) { null }
        }
        return current
    }

    /** An active job this process is not running: the process died or the system stopped it. */
    fun interrupted(filesDir: File): ProcessingJob? = load(filesDir)?.takeIf { !running && (it.phase.active || it.phase == ProcessingJob.Phase.PAUSED) }

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }

    /** Publishes [job]; [persist] also writes it, which progress ticks skip. */
    fun update(filesDir: File, job: ProcessingJob?, persist: Boolean) {
        synchronized(this) { loaded = true; current = job }
        if (persist) save(filesDir, job)
        main.post { if (current === job) listeners.forEach { it.onJobChanged(job) } }
    }

    @Synchronized private fun save(filesDir: File, job: ProcessingJob?) {
        val target = file(filesDir)
        if (job == null) { target.delete(); return }
        directory(filesDir).mkdirs()
        val stream = target.startWrite()
        try {
            stream.write(job.json().toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) { target.failWrite(stream); throw error }
    }

    /** The job of [noteId] that is still working or stopped short, if any. */
    fun forNote(noteId: String?): ProcessingJob? = current?.takeIf { noteId != null && it.noteId == noteId }
}
