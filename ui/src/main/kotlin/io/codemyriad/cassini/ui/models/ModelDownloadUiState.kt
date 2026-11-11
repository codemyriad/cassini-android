package io.codemyriad.cassini.ui.models

import androidx.compose.runtime.Immutable
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.format.ModelPartUi
import kotlinx.collections.immutable.ImmutableList

/**
 * The Model download screen (board 05, features.md section 5; ui.md section 7). [parts] are the missing parts with
 * their sizes in bytes. The screen sums the rounded part sizes, so the state has no total. The list can be empty
 * when queuing an already-ready note fails. [requiredFreeBytes] is the footnote figure. [withNote] picks
 * "Download and transcribe" over "Download". [busy] shows the primary button's spinner, and [banner] explains
 * storage or enqueue failures.
 */
@Immutable
data class ModelDownloadUiState(
    val parts: ImmutableList<ModelPartSizeUi>,
    val requiredFreeBytes: Long,
    val withNote: Boolean,
    val busy: Boolean = false,
    val banner: BannerSpec? = null,
)

/** One missing part and its download size in bytes. */
@Immutable
data class ModelPartSizeUi(val part: ModelPartUi, val bytes: Long)
