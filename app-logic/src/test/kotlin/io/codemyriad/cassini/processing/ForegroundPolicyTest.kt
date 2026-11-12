package io.codemyriad.cassini.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundPolicyTest {
    @Test fun theForegroundNeedFollowsThePhaseAndTheApiLevel() {
        assertEquals(ForegroundNeed.NONE, ForegroundPolicy.need(35, processing = false, working = false))
        assertEquals(ForegroundNeed.DATA_SYNC, ForegroundPolicy.need(35, processing = false, working = true))
        for (api in listOf(26, 29, 34)) {
            assertEquals(ForegroundNeed.DATA_SYNC, ForegroundPolicy.need(api, processing = true, working = true))
        }
        assertEquals(ForegroundNeed.MEDIA_PROCESSING, ForegroundPolicy.need(35, processing = true, working = true))
    }
}
