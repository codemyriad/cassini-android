package org.cassini.ui.banner

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import org.cassini.ui.R
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Durations
import org.cassini.ui.format.FormatSettings
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.Sizes

/**
 * The copy of a [BannerSpec] in the shown language: the kind's resources with its [BannerKind.uses] arguments
 * formatted (features.md section 13). A pure form takes [Resources]; a composable form reads the composition.
 */
object BannerText {
    private const val PERCENT_MAX = 100L

    fun title(res: Resources, spec: BannerSpec, format: FormatSettings): String =
        title(res, spec.kind, spec.args, format)

    fun title(res: Resources, kind: BannerKind, args: BannerArgs, format: FormatSettings): String =
        when (kind) {
            BannerKind.REC_STORAGE_LOW -> res.getString(
                if (args.fileLimit) R.string.ui_banner_rec_file_low_title else kind.title,
                Durations.atLeastOneMinute(res, args.ms1),
            )
            BannerKind.RECOVERED, BannerKind.TX_QUEUE_HELD -> Counts.plural(res, kind.title, args.count, format)
            BannerKind.TX_PAUSED -> res.getString(kind.title, Durations.clock(args.ms1), Durations.clock(args.ms2))
            BannerKind.DL_DOWNLOADING -> (args.part ?: ModelPartUi.SPEECH).downloading(res, downloadPercent(args))
            BannerKind.DL_CHECKING -> res.getString(kind.title, args.percent ?: 0)
            BannerKind.LIB_NO_MATCH, BannerKind.IMPORTING -> res.getString(kind.title, args.text.orEmpty())
            else -> res.getString(kind.title)
        }

    fun body(res: Resources, spec: BannerSpec, format: FormatSettings): String? =
        body(res, spec.kind, spec.args, format)

    fun body(res: Resources, kind: BannerKind, args: BannerArgs, format: FormatSettings): String? =
        when (kind) {
            BannerKind.RECOVERED -> res.getString(kind.body, Durations.clock(args.ms1))
            BannerKind.REC_STOPPED_STORAGE -> res.getString(kind.body, Sizes.megabytes(res, args.bytes, format))
            // The app's reason why there is no transcript (too long to transcribe), else "No transcript yet."
            BannerKind.PLAIN_AUDIO -> args.text?.takeIf { it.isNotBlank() } ?: res.getString(kind.body)
            BannerKind.TX_FAILED ->
                args.text?.takeIf { it.isNotBlank() }?.let { res.getString(kind.body, it) }
                    ?: res.getString(R.string.ui_banner_tx_failed_body_plain)
            BannerKind.DL_DOWNLOADING -> Sizes.downloaded(res, args.bytes, args.bytes2, format)
            BannerKind.DL_NO_SPACE -> res.getString(kind.body, Sizes.megabytes(res, args.bytes, format))
            BannerKind.IMPORT_NO_SPACE -> res.getString(kind.body, Sizes.storage(res, args.bytes, format))
            BannerKind.NO_ENCODER, BannerKind.DL_STOPPED, BannerKind.TX_PAUSED, BannerKind.TX_PAUSED_EARLY,
            BannerKind.REC_STORAGE_LOW,
            -> res.getString(variantBody(kind, args))
            else -> if (kind.body == 0) null else res.getString(kind.body)
        }

    /**
     * The body of a kind with a second copy: NO_ENCODER without M4A; DL_STOPPED, TX_PAUSED and TX_PAUSED_EARLY after
     * a stop by Android (the daily time limit of background work on Android 15 and later); REC_STORAGE_LOW for the
     * room left in an AAC file.
     */
    @StringRes
    private fun variantBody(kind: BannerKind, args: BannerArgs): Int = when {
        kind == BannerKind.NO_ENCODER && !args.m4a -> R.string.ui_banner_no_encoder_body
        kind == BannerKind.DL_STOPPED && args.bySystem -> R.string.ui_banner_dl_stopped_system_body
        kind == BannerKind.REC_STORAGE_LOW && args.fileLimit -> R.string.ui_banner_rec_file_low_body
        (kind == BannerKind.TX_PAUSED || kind == BannerKind.TX_PAUSED_EARLY) && args.bySystem ->
            R.string.ui_banner_tx_paused_system_body
        else -> kind.body
    }

    fun action(res: Resources, action: BannerAction): String = res.getString(action.label)

    /** [BannerArgs.percent], or the share of [BannerArgs.bytes] in [BannerArgs.bytes2] when it is null. */
    private fun downloadPercent(args: BannerArgs): Int =
        args.percent ?: if (args.bytes2 <= 0) {
            0
        } else {
            (args.bytes * PERCENT_MAX / args.bytes2).coerceIn(0, PERCENT_MAX).toInt()
        }

    @Composable
    @ReadOnlyComposable
    fun title(spec: BannerSpec): String = title(LocalResources.current, spec, LocalFormat.current)

    @Composable
    @ReadOnlyComposable
    fun body(spec: BannerSpec): String? = body(LocalResources.current, spec, LocalFormat.current)

    @Composable
    @ReadOnlyComposable
    fun action(action: BannerAction): String = action(LocalResources.current, action)
}
