package org.cassini.android

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Manual check after killing a live capture: `-e recoveryCheck true [-e maxBytes N]`. Recovers like the library does, then decodes and times the newest captures. */
class RecoveryCheckTest {
    @Test fun recoveredCapturesDecodeToTheirCataloguedLength() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        assumeTrue("Run with -e recoveryCheck true after killing a recording", InstrumentationRegistry.getArguments().getString("recoveryCheck") == "true")
        RecoveryScanner.recover(context.filesDir, RecordingService.live, context.getString(R.string.recording_recovered))
        val notes = LibraryStore(context.filesDir).load().associateBy { it.session.uri }
        val captures = File(context.filesDir, "documents").listFiles().orEmpty().filter { it.name.endsWith(".rec.opus") }.sortedBy { it.lastModified() }
        captures.filter { InstrumentationRegistry.getArguments().getString("maxBytes")?.toLong()?.let { max -> it.length() <= max } ?: true }.takeLast(5).forEach { file ->
            val note = notes[Uri.fromFile(file).toString()]
            val started = android.os.SystemClock.elapsedRealtime()
            val decoded = AudioDecoder.decode(context, Uri.fromFile(file))
            val decodeMs = android.os.SystemClock.elapsedRealtime() - started
            val line = "${file.name} modified=${file.lastModified()} bytes=${file.length()} clean=${RecoveryScanner.endsCleanly(file)} catalogued=${note?.session?.name}/${note?.session?.durationMs} decodedMs=${decoded.durationMs} decodeTookMs=$decodeMs"
            android.util.Log.i("CassiniRecoveryCheck", line)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", line + "\n") })
            assertTrue(line, RecoveryScanner.endsCleanly(file) && note != null && kotlin.math.abs(decoded.durationMs - note.session.durationMs) <= 100)
        }
    }
}
