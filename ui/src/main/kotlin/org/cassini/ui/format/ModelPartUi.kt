package org.cassini.ui.format

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R

/**
 * The three downloadable parts (features.md sections 5 and 11), with the row name and the download banner title
 * ("Downloading speech recognition — 46%").
 */
enum class ModelPartUi(@StringRes val label: Int, @StringRes val downloading: Int) {
    SPEECH(R.string.ui_part_speech, R.string.ui_part_downloading_speech),
    SPEAKERS(R.string.ui_part_speakers, R.string.ui_part_downloading_speakers),
    VOICES(R.string.ui_part_voices, R.string.ui_part_downloading_voices),
    ;

    fun label(res: Resources): String = res.getString(label)

    fun downloading(res: Resources, percent: Int): String = res.getString(downloading, percent)

    @Composable
    @ReadOnlyComposable
    fun label(): String = label(resources())

    @Composable
    @ReadOnlyComposable
    fun downloading(percent: Int): String = downloading(resources(), percent)
}
