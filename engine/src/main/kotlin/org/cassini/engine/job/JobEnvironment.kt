package org.cassini.engine.job

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

/** The native seams one [TranscriptionJob] runs on (JobSeams.kt). `AppGraph` builds them from the installed models. */
class JobEngines(
    val recognizers: RecognizerFactory,
    val diarizers: DiarizerFactory,
    val prints: PrintExtractor,
    val audio: AudioSource,
    val encoders: OpusSinkFactory,
)

/**
 * Threads, clock and app-provided details of a [TranscriptionJob].
 *
 * [placeholderLabel] is the label of the n-th speaker (1-based, in order of first word) that has no name: "Speaker 2"
 * in the app language (`ui_speaker_label` in :ui). [nanoTime] is the monotonic clock (`System.nanoTime` in the app, a
 * fake in tests). [speakerPrefix] makes the prefix of a fresh run's speaker ids. [lowMemory] is the memory preflight
 * (architecture section 5.7) for the known duration (null when not known): it only adds a hint to the progress card.
 * [warn] logs a best-effort failure. [syncFile] runs the fsync of a work file (`clips.pcm`, `work/sealed.opus`);
 * tests use it to record that the fsync comes before the checkpoint or the marker that counts on it.
 */
@Suppress("LongParameterList") // one hook per thing the app provides; all but the label have defaults
class JobEnvironment(
    val placeholderLabel: (Int) -> String,
    val inference: CoroutineDispatcher = InferenceDispatcher.dispatcher,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val nanoTime: () -> Long = System::nanoTime,
    val speakerPrefix: () -> String = { "diar_${UUID.randomUUID().toString().replace('-', '_')}_" },
    val lowMemory: (durationMs: Long?) -> Boolean = { false },
    val warn: (String, Throwable) -> Unit = { _, _ -> },
    val syncFile: (File, () -> Unit) -> Unit = { _, sync -> sync() },
)
