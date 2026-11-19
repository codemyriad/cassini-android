package io.codemyriad.cassini

import android.app.Application
import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [CassiniApplicationTest.ShadowOs::class])
class CassiniApplicationTest {
    private val envKeys = listOf("ORT_DISABLE_TELEMETRY", "NEEDLE_TELEMETRY", "DO_NOT_TRACK")

    @Test
    fun coldStartSetsOrtDisableTelemetryInMainProcessBeforeGraphStarts() {
        val app = ApplicationProvider.getApplicationContext<CassiniApplication>()
        assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"))
        assertFalse(app.hasStartedGraph())
    }

    @Test
    fun mainProcessStartupOverwritesMissingAndInheritedZeroOrtTelemetry() {
        val app = ApplicationProvider.getApplicationContext<CassiniApplication>()
        withSavedEnv {
            for (initial in listOf(null, "0")) {
                if (initial == null) {
                    Os.unsetenv("ORT_DISABLE_TELEMETRY")
                } else {
                    Os.setenv("ORT_DISABLE_TELEMETRY", initial, true)
                }
                Os.unsetenv("NEEDLE_TELEMETRY")
                Os.unsetenv("DO_NOT_TRACK")

                app.onCreate()

                assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"))
                assertNull(Os.getenv("NEEDLE_TELEMETRY"))
                assertNull(Os.getenv("DO_NOT_TRACK"))
            }
        }
    }

    @Test
    fun whistleWorkerStartupSetsAllPrivacyFlagsWithoutStartingMainGraph() {
        val app = ApplicationProvider.getApplicationContext<CassiniApplication>()
        val originalProcess = Application.getProcessName()
        withSavedEnv {
            try {
                val workerProcess = "${app.packageName}:whistle"
                ShadowApplication.setProcessName(workerProcess)
                ShadowProcess.setProcessName(workerProcess)
                assertEquals(workerProcess, Application.getProcessName())

                for (initial in listOf(null, "0")) {
                    if (initial == null) {
                        Os.unsetenv("ORT_DISABLE_TELEMETRY")
                    } else {
                        Os.setenv("ORT_DISABLE_TELEMETRY", initial, true)
                    }
                    Os.unsetenv("NEEDLE_TELEMETRY")
                    Os.unsetenv("DO_NOT_TRACK")

                    val workerApp = CassiniApplication()
                    ReflectionHelpers.callInstanceMethod<Unit>(
                        workerApp,
                        "attach",
                        ReflectionHelpers.ClassParameter.from(Context::class.java, app.baseContext),
                    )
                    workerApp.onCreate()

                    assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"))
                    assertEquals("0", Os.getenv("NEEDLE_TELEMETRY"))
                    assertEquals("1", Os.getenv("DO_NOT_TRACK"))
                    assertFalse(workerApp.hasStartedGraph())
                    assertThrows(UninitializedPropertyAccessException::class.java) {
                        workerApp.backupDirectory
                    }
                }
            } finally {
                ShadowApplication.setProcessName(originalProcess)
                ShadowProcess.setProcessName(originalProcess)
            }
        }
    }

    private fun withSavedEnv(block: () -> Unit) {
        val saved = envKeys.associateWith { Os.getenv(it) }
        try {
            block()
        } finally {
            saved.forEach { (key, value) ->
                if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true)
            }
        }
    }

    @Suppress("UtilityClassWithPublicConstructor") // Robolectric shadows require a public no-arg constructor.
    @Implements(Os::class)
    class ShadowOs {
        companion object {
            private val env = LinkedHashMap<String, String>()

            @Implementation
            @JvmStatic
            fun getenv(name: String): String? = env[name]

            @Implementation
            @JvmStatic
            fun setenv(name: String, value: String, overwrite: Boolean) {
                if (overwrite || !env.containsKey(name)) {
                    env[name] = value
                }
            }

            @Implementation
            @JvmStatic
            fun unsetenv(name: String) {
                env.remove(name)
            }
        }
    }
}
