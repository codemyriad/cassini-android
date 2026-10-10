package org.cassini.ui.banner

import android.content.res.Resources
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
            BannerKind.REC_STORAGE_LOW -> res.getString(kind.title, Durations.atLeastOneMinute(res, args.ms1))
            BannerKind.REC_NEAR_LIMIT -> {
                val minutes = ((args.ms1 + Durations.MINUTE_MS - 1) / Durations.MINUTE_MS).toInt().coerceAtLeast(1)
                Counts.plural(res, kind.title, minutes, format)
            }
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
            BannerKind.TX_FAILED ->
                args.text?.takeIf { it.isNotBlank() }?.let { res.getString(kind.body, it) }
                    ?: res.getString(R.string.ui_banner_tx_failed_body_plain)
            BannerKind.DL_DOWNLOADING -> Sizes.downloaded(res, args.bytes, args.bytes2, format)
            BannerKind.DL_NO_SPACE -> res.getString(kind.body, Sizes.megabytes(res, args.bytes, format))
            BannerKind.IMPORT_NO_SPACE -> res.getString(kind.body, Sizes.storage(res, args.bytes, format))
            BannerKind.NO_ENCODER -> res.getString(if (args.m4a) kind.body else R.string.ui_banner_no_encoder_body)
            else -> if (kind.body == 0) null else res.getString(kind.body)
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
