package org.cassini.ui.models

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.format.ModelPartUi

/**
 * The Model download screen (board 05, features.md section 5; ui.md section 7). [parts] are the missing parts with
 * their sizes in bytes; never empty. [totalBytes] is the sum of the rounded part sizes (640 + 62 + 28 MB reads
 * "730 MB"). [requiredFreeBytes] is the footnote figure. [withNote] picks "Download and transcribe" over
 * "Download"; [busy] shows the primary button's spinner; [banner] is the no-space or connection banner.
 */
@Immutable
data class ModelDownloadUiState(
    val parts: ImmutableList<ModelPartSizeUi>,
    val totalBytes: Long,
    val requiredFreeBytes: Long,
    val withNote: Boolean,
    val busy: Boolean = false,
    val banner: BannerSpec? = null,
)

/** One missing part and its download size in bytes. */
@Immutable
data class ModelPartSizeUi(val part: ModelPartUi, val bytes: Long)
