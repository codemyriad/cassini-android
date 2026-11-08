package io.codemyriad.cassini.whistle

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.codemyriad.cassini.engine.decode.AudioDecoder
import io.codemyriad.cassini.engine.job.AudioSource
import io.codemyriad.cassini.engine.job.DecoderAudioSource
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.whistle.WhistleReplay
import java.io.File

/** Binds one immutable job to Included's decoder and worker, preserving its optional speaker/print seams. */
object WhistleJobs {
    @RequiresApi(Build.VERSION_CODES.Q)
    fun of(context: Context, base: JobEngines, cache: File, file: File): JobEngines {
        val source = DecoderAudioSource(AudioDecoder(context.contentResolver, cache, included = true))
        val replay = WhistleReplay { samples, sink -> prefix(source, file, samples, sink) }
        return JobEngines(WhistleRecognizers(context, replay), base.diarizers, base.prints, source, base.encoders)
    }

    private fun prefix(source: AudioSource, file: File, samples: Long, sink: (FloatArray, Int) -> Unit) {
        if (samples == 0L) return
        var remaining = samples
        try {
            source.stream(file, 0, { block, count ->
                val size = minOf(remaining, count.toLong()).toInt()
                sink(block, size)
                remaining -= size
                if (remaining == 0L) throw PrefixComplete()
            }, { _, _ -> })
        } catch (_: PrefixComplete) {
            // Stop the decoder at the saved sample, preserving the worker's unflushed track state.
        }
        check(remaining == 0L) { "Source ended before saved Whistle progress" }
    }

    private class PrefixComplete : RuntimeException()
}
