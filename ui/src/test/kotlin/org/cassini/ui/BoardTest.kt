package org.cassini.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The base of every screenshot test in :ui (docs/design/ui.md section 10.1). The class qualifiers give the boards'
 * 390 x 844 dp phone at 2x (780 x 1688 px). A test or subclass adds to them with `+`:
 *
 * - dark: `@Config(qualifiers = "+night")` and `board(..., dark = true)`;
 * - Italian: `@Config(qualifiers = "+it")` and `override val locale = Locale.ITALIAN`;
 * - font scale: `@Config(fontScale = 1.5f)` and `override val fontScale = 1.5f`.
 *
 * [show] checks that the configuration and the overrides agree, so the resources (strings, `values-night`) and the
 * Compose side never disagree in a golden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = BoardTest.PHONE)
abstract class BoardTest {
    @get:Rule val rule = createComposeRule()

    /** The locale of [ProvideFixedFormat]; the resources follow the qualifiers. */
    open val locale: Locale = Locale.ENGLISH

    /** The font scale the test expects; set it with `@Config(fontScale = ...)`. */
    open val fontScale: Float = 1f

    /**
     * Renders [content] in [CassiniTheme] with the fixed format and captures it to
     * `src/test/screenshots/<dir>/<name>.png`. [window] captures the whole screen with every window (a real
     * dialog, menu or sheet) instead of the root node.
     */
    fun board(
        dir: String,
        name: String,
        dark: Boolean = false,
        window: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        show(dark, content)
        capture(dir, name, window)
    }

    /** Captures what is on screen now to `src/test/screenshots/<dir>/<name>.png`, as [board] does after [show]. */
    fun capture(dir: String, name: String, window: Boolean = false) {
        val path = "src/test/screenshots/$dir/$name.png"
        if (window) {
            captureScreenRoboImage(path, roborazziOptions = OPTIONS)
        } else {
            rule.onRoot().captureRoboImage(path, roborazziOptions = OPTIONS)
        }
    }

    /** Sets the content as [board] does, with the main clock paused and then advanced by one second, and checks the configuration. */
    fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        lateinit var configuration: Configuration
        var densityFontScale = 0f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            configuration = LocalConfiguration.current
            densityFontScale = LocalDensity.current.fontScale
            CassiniTheme(darkTheme = dark) {
                ProvideFixedFormat(locale = locale, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
        rule.mainClock.advanceTimeBy(CLOCK_ADVANCE_MS)
        val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        assertEquals("dark = $dark needs @Config(qualifiers = \"+night\") (and only then)", dark, night)
        val language = configuration.locales[0].language
        assertEquals("the locale qualifier must match the locale override", locale.language, language)
        assertEquals("set @Config(fontScale = ...)", fontScale, configuration.fontScale, FONT_SCALE_DELTA)
        assertEquals("Compose did not pick up the font scale", fontScale, densityFontScale, FONT_SCALE_DELTA)
    }

    companion object {
        /** The boards' phone: 390 x 844 dp at xhdpi (2x). */
        const val PHONE = "w390dp-h844dp-xhdpi"

        /** The "now" of every fixture: 9 October 2026, 10:30 in Rome. */
        val FIXED_NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 9, 10, 30, 0, 0, FIXED_ZONE)

        /** A clock that always reads [FIXED_NOW], for fixtures that classify dates as a ViewModel would. */
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW.toInstant(), FIXED_ZONE)

        private const val CLOCK_ADVANCE_MS = 1_000L
        private const val FONT_SCALE_DELTA = 0.001f
        private const val CHANGE_THRESHOLD = 0.0005f
        private val OPTIONS = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
        )
    }
}
