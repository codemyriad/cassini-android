package org.cassini.core.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeakerBudgetTest {
    /**
     * The patch is pinned by hash with the AAR, so it cannot name [SpeakerBudget]; this test keeps the two bounds
     * equal instead.
     */
    @Test fun theDiarizerStateCapIsTheBoundTheJniPatchEnforces() {
        val patch = File(System.getProperty("cassini.diarizationPatch", "../scripts/streaming-diarization.patch")!!)
        assertTrue("Missing $patch", patch.isFile)
        val restore = patch.readText().substringAfter("OfflineSpeakerDiarization_restoreStreamingState(")
        val bound = Regex("""if \(n > (\d+)\) return false;""").find(restore)
        assertTrue("No state bound in restoreStreamingState of $patch", bound != null)
        assertEquals(SpeakerBudget.DIARIZER_STATE_FLOATS, bound!!.groupValues[1].toInt())
    }
}
