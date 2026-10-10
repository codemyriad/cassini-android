package org.cassini.ui.components

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.boardArgs
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi

/** Transcript, player and progress fixtures for the batch-2 strips and tests (boards 02 and 03 copy). */
internal object TranscriptFixtures {
    /** Words of [text] split at spaces, 400 ms apart from [startMs]. */
    fun words(text: String, startMs: Long = 0): ImmutableList<WordUi> =
        text.split(' ').mapIndexed { index, word -> WordUi(word, startMs + index * 400L) }.toImmutableList()

    /** Every occurrence of [query] in the paragraph text of [words], as search would report it. */
    fun matchesOf(words: List<WordUi>, query: String): ImmutableList<IntRange> =
        Regex(Regex.escape(query)).findAll(turnText(words)).map { it.range }.toList().toImmutableList()

    val giulia = words(
        "Ok, then let's confirm the quantities first. We said four hundred units for the first batch, right?",
        692_000,
    )

    /** Board 03's second turn; word 8 is "quindi", the active word. */
    val paolo = words(
        "Sì, quattrocento. Il problema è la seconda consegna: quindi la consegna slitta a giovedì, but the price " +
            "stays as agreed in the March order.",
        701_000,
    )
    const val PAOLO_ACTIVE = 8

    val speaker3 = words(
        "Can you send that in writing? I need it for the purchase order before Friday.",
        740_000,
    )

    /** A turn whose word 3 wraps at its hyphens in a narrow column. */
    val wrapped = words("We moved the end-to-end-encryption-review to Friday, after the release.")
    const val WRAPPED_ACTIVE = 3

    val draftGiulia = words(
        "So for the October release we keep the import flow as it is, and we move the onboarding changes to " +
            "November.",
        1_711_000,
    )

    /** Board 02's last paragraph; from word 9 on the words are unsettled. */
    val draftAfterCard = words(
        "Va bene, però allora dobbiamo avvisare il supporto entro venerdì, otherwise they'll get the tickets " +
            "before the",
        1_745_000,
    )
    const val DRAFT_UNSETTLED_FROM = 9

    /** Board 02's card. */
    val boardCard = ProcessingCardUi(
        headline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
        rangeStartMs = 1_740_000,
        rangeEndMs = 1_785_000,
        percent = 62,
        stage = StageUi.WORDS_AND_VOICES,
        skippedMatching = false,
        timeLeft = TimeLeftUi.Range(360_000, 540_000),
        doneMs = 1_792_000,
        totalMs = 2_893_000,
        battery = BatteryUi.Projected(54, 41),
        words = 4_812,
        lowMemory = false,
    )

    /** The first seconds: nothing measured yet, low memory. */
    val startingCard = ProcessingCardUi(
        headline = ProgressHeadline.STARTING,
        rangeStartMs = 0,
        rangeEndMs = 0,
        percent = 0,
        stage = StageUi.READING,
        skippedMatching = false,
        timeLeft = TimeLeftUi.Calculating,
        doneMs = 0,
        totalMs = 2_052_000,
        battery = BatteryUi.Measuring(80),
        words = 0,
        lowMemory = true,
    )

    /** Saving, with Matching skipped (no saved voices), plugged in. */
    val savingCard = ProcessingCardUi(
        headline = ProgressHeadline.SAVING,
        rangeStartMs = 0,
        rangeEndMs = 0,
        percent = 99,
        stage = StageUi.SAVING,
        skippedMatching = true,
        timeLeft = TimeLeftUi.Finishing,
        doneMs = 4_325_000,
        totalMs = 4_325_000,
        battery = BatteryUi.PluggedIn(35),
        words = 12_345,
        lowMemory = false,
    )

    /** Starting while the speech model downloads: the board copy of the DL_DOWNLOADING banner. */
    val downloadingCard = boardArgs(BannerKind.DL_DOWNLOADING).let { args ->
        startingCard.copy(
            lowMemory = false,
            download = DownloadUi(
                part = requireNotNull(args.part),
                percent = requireNotNull(args.percent),
                bytes = args.bytes,
                totalBytes = args.bytes2,
            ),
        )
    }
}
