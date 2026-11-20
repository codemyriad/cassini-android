package io.codemyriad.cassini.whistle

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WhistleCachedSegment
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.job.RecognizerFactory
import io.codemyriad.cassini.engine.whistle.WhistleRecognizer
import io.codemyriad.cassini.engine.whistle.WhistleReplay
import io.codemyriad.cassini.engine.whistle.WhistleResult
import io.codemyriad.cassini.engine.whistle.WhistleSegmentStore
import io.codemyriad.cassini.engine.whistle.WhistleTransport

/** The job seam over the isolated worker; the durable job supplies its exact pinned source-prefix replay. */
@RequiresApi(Build.VERSION_CODES.Q)
class WhistleRecognizers(
    private val context: Context,
    private val replay: WhistleReplay,
    private val segmentStore: WhistleSegmentStore? = null,
    private val onWorkerFinished: (Bundle) -> Unit = {},
) : RecognizerFactory {
    override fun open(restored: RecognitionState?, callbacks: Recognizer.Callbacks): Recognizer =
        WhistleRecognizer(
            restored,
            callbacks,
            replay,
            { segment ->
                val worker = WhistleWorker(context, RATE) {
                    segment(
                        WhistleResult(
                            start = it.getLong("start"),
                            end = it.getLong("end"),
                            json = requireNotNull(it.getByteArray("json")),
                            pcmSha256 = it.getString("pcmSha256").orEmpty(),
                        ),
                    )
                }
                object : WhistleTransport {
                    override fun seedCache(entries: List<WhistleCachedSegment>) = worker.seedCache(entries)
                    override fun accept(samples: FloatArray, count: Int) = worker.accept(samples, count)
                    override fun finish() {
                        onWorkerFinished(worker.finish())
                    }
                    override fun close() = worker.close()
                }
            },
            segmentStore = segmentStore,
        )

    companion object {
        private const val RATE = 16000
    }
}
