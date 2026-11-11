package io.codemyriad.cassini.library

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class SearchCalendarTest {
    @Test fun theInjectedZoneIsUsedForTheRetainedValueAndEveryBroadcast() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var zone = ZoneId.of("Pacific/Kiritimati")
        searchCalendar(context) { zone }.test {
            assertEquals(zone, awaitItem())
            zone = ZoneId.of("America/Havana")
            context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(zone, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun retainedZoneThenClockAndCalendarBroadcastsRefreshWithoutPolling() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
            searchCalendar(context).test {
                assertEquals(ZoneId.of("Europe/Rome"), awaitItem())
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
                context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(ZoneId.of("America/New_York"), awaitItem())
                for (action in listOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED)) {
                    context.sendBroadcast(Intent(action))
                    shadowOf(Looper.getMainLooper()).idle()
                    // Equal zones still emit: the ViewModel recomputes the date from its injected clock.
                    assertEquals(ZoneId.of("America/New_York"), awaitItem())
                }
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
