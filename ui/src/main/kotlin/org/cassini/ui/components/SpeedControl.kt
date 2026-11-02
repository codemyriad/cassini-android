package org.cassini.ui.components

import androidx.compose.runtime.Immutable

/** The speed button of the player (coordination D53): the current [speed] (1 = normal) and what a tap does. */
@Immutable
data class SpeedControl(val speed: Float, val onClick: () -> Unit)
