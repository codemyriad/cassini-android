package io.codemyriad.cassini.engine.whistle

/**
 * Verifies that post-Stop Whistle transcription executes zero neural inference passes on segments already settled
 * into a recording-time precomputation checkpoint (`D109` / `D-1126`).
 */
object WhistlePrefixAudit {
    fun spanKey(startSample: Long, endSample: Long, pcmSha256: String): String =
        "$startSample-$endSample:$pcmSha256"

    fun verifyNoNeuralPrefixRedecode(
        precomputedPrefixSpans: List<String>,
        postStopCacheHitSpans: List<String>,
        postStopNeuralSpans: List<String>,
        baselineTotalSegments: Int? = null,
    ) {
        val error = findViolation(
            precomputedPrefixSpans,
            postStopCacheHitSpans,
            postStopNeuralSpans,
            baselineTotalSegments,
        )
        if (error != null) throw AssertionError(error)
    }

    private fun findViolation(
        precomputedPrefixSpans: List<String>,
        postStopCacheHitSpans: List<String>,
        postStopNeuralSpans: List<String>,
        baselineTotalSegments: Int?,
    ): String? {
        val prefixSet = precomputedPrefixSpans.toSet()
        val redecodedPrefix = postStopNeuralSpans.filter { it in prefixSet }
        val leadingHits = postStopCacheHitSpans.take(precomputedPrefixSpans.size)
        val totalPostStop = postStopCacheHitSpans.size + postStopNeuralSpans.size
        return when {
            precomputedPrefixSpans.isEmpty() ->
                "Expected non-empty precomputed Whistle prefix spans"
            redecodedPrefix.isNotEmpty() ->
                "Whistle executed neural inference on precomputed prefix segments after Stop: $redecodedPrefix"
            leadingHits != precomputedPrefixSpans ->
                "Whistle post-Stop leading cache hits ($leadingHits) != precomputed prefix ($precomputedPrefixSpans)"
            baselineTotalSegments != null && totalPostStop != baselineTotalSegments ->
                "Whistle post-Stop total segments ($totalPostStop) != baseline ($baselineTotalSegments)"
            else -> null
        }
    }
}
