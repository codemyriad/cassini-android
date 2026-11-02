package org.cassini.android.playback

import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Review of #40: the session shows the note's title and takes commands, so only trusted controllers connect. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class TrustedControllersTest {
    private val player = object : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder().build()
    }
    private val session = MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
        .setCallback(TrustedControllers)
        .build()

    @After fun release() = session.release()

    private fun controller(packageName: String, uid: Int, trusted: Boolean = false) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            0,
            uid,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            trusted,
            Bundle.EMPTY,
            true,
        )

    @Test fun anotherAppIsRefused() {
        assertFalse(TrustedControllers.trusted(session, controller("com.example.reader", OTHER_APP_UID)))
    }

    @Test fun theAppTheSystemAndTrustedControllersConnect() {
        assertTrue(TrustedControllers.trusted(session, controller("org.cassini.android", Process.myUid())))
        assertTrue(TrustedControllers.trusted(session, controller("android", Process.SYSTEM_UID)))
        assertTrue(TrustedControllers.trusted(session, controller("com.android.systemui", SYSTEM_UI_UID)))
        assertTrue(TrustedControllers.trusted(session, controller("com.android.bluetooth", BLUETOOTH_UID)))
        // MEDIA_CONTENT_CONTROL or an enabled notification listener, as a watch companion app has.
        assertTrue(TrustedControllers.trusted(session, controller("com.example.watch", OTHER_APP_UID, trusted = true)))
    }

    private companion object {
        const val OTHER_APP_UID = 10_999
        const val SYSTEM_UI_UID = 10_050
        const val BLUETOOTH_UID = 1_002
    }
}
