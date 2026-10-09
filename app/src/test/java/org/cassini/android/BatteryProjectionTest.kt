package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class BatteryProjectionTest {
    @Test fun estimatesFromMeasuredDischargeAndClampsAtZero() {
        val meter = BatteryProjection()
        assertNull(meter.sample(BatteryProjection.Reading(0, 80, false, 4000000), 600000).percentAfter)
        assertNull(meter.sample(BatteryProjection.Reading(60000, 79, false, 3950000), 600000).percentAfter)
        assertEquals(69, meter.sample(BatteryProjection.Reading(120000, 79, false, 3900000), 600000).percentAfter)
        assertEquals(0, meter.sample(BatteryProjection.Reading(120000, 79, false, 3900000), 60000000).percentAfter)
    }
    @Test fun chargingAndResumingNeedFreshMeasurements() {
        val meter = BatteryProjection()
        meter.sample(BatteryProjection.Reading(0, 80, false), 600000)
        assertTrue(meter.sample(BatteryProjection.Reading(120000, 80, true), 600000).charging)
        assertNull(meter.sample(BatteryProjection.Reading(600000, 78, false), 600000).percentAfter)
        assertEquals(66, meter.sample(BatteryProjection.Reading(720000, 76, false), 600000).percentAfter)
        assertNull(BatteryProjection().sample(BatteryProjection.Reading(720000, 76, false), 600000).percentAfter)
    }
    @Test fun noDropOrUnknownEtaCannotProduceAProjection() {
        val meter = BatteryProjection()
        meter.sample(BatteryProjection.Reading(0, 50, false), 600000)
        assertNull(meter.sample(BatteryProjection.Reading(300000, 50, false), 600000).percentAfter)
        assertNull(meter.sample(BatteryProjection.Reading(400000, 49, false), null).percentAfter)
    }
}
