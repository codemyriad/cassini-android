package org.cassini.android

/** Sequential inference on a bounded recording buffer. Capture never waits for this consumer. */
internal object LiveTranscription {
    const val PROVENANCE = "live 5 s steps with 1 s recorded overlap, 0.5 s synthetic tail, seams spliced at an aligned word"

    fun transcribe(audio: LiveAudioBuffer, decode: (PcmAudio, Int) -> List<TimedWord>,
                   onProgress: (Parakeet.Progress) -> Unit): Transcript {
        val began = System.nanoTime()
        var end = 0
        var merged = emptyList<TimedWord>()
        while (true) {
            requireUser(!audio.cancelled && !Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val span = audio.awaitChunk(end) ?: break
            val chunk = audio.snapshot(span.start, span.end)
            val placed = WordGate.offsetDecoderWords(decode(chunk, chunk.sampleRate / 2),
                SpeechWindows.floorMs(span.start, chunk.sampleRate), 0)
            val found = WordGate.clampWordsToTimelineEnd(placed, SpeechWindows.floorMs(span.end, chunk.sampleRate), 500)
            // Choose one reading on either side of an agreed word. Keeping both unmatched readings
            // can repeat a boundary word when one chunk mishears its ending ("sentinelle" / "sentinella").
            merged = if (end == 0) found else SeamMerge.splice(merged, found, SpeechWindows.floorMs(span.start, chunk.sampleRate),
                SpeechWindows.floorMs((end - span.start).coerceAtLeast(0), chunk.sampleRate)) { it.word }
            end = span.end
            onProgress(Parakeet.Progress(SpeechWindows.floorMs(end, chunk.sampleRate),
                SpeechWindows.floorMs(audio.size, chunk.sampleRate), (System.nanoTime() - began) / 1_000_000, merged.map { it.word }))
        }
        requireUser(!audio.cancelled && !Thread.currentThread().isInterrupted, Failure.CANCELLED)
        val recording = audio.snapshot()
        return Transcript(WordGate.finalizeTranscriptWords(recording, merged, 500))
    }
}
