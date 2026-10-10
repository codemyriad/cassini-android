package org.cassini.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.cassini.ui.R

// The bundled static TTFs, byte-identical to upstream (third_party/fonts/fonts.json, docs/design/ui.md
// section 3). Plex Mono ships 400 and 500 only: Compose synthesises 600 from 500.

/** Instrument Sans 400, 500, 600 and 700: the text face of every screen. */
val InstrumentSans: FontFamily = FontFamily(
    Font(R.font.ui_instrument_sans_regular, FontWeight.W400),
    Font(R.font.ui_instrument_sans_medium, FontWeight.W500),
    Font(R.font.ui_instrument_sans_semibold, FontWeight.W600),
    Font(R.font.ui_instrument_sans_bold, FontWeight.W700),
)

/** IBM Plex Mono 400 and 500: times, durations, sizes and counters. */
val PlexMono: FontFamily = FontFamily(
    Font(R.font.ui_ibm_plex_mono_regular, FontWeight.W400),
    Font(R.font.ui_ibm_plex_mono_medium, FontWeight.W500),
)
