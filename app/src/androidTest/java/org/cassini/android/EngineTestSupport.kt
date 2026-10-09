package org.cassini.android

import android.content.Context
import org.cassini.engine.decode.AudioDecoder

/** The engine's decoder over [context]'s content resolver and cache directory, as the app builds it. */
internal fun audioDecoder(context: Context) = AudioDecoder(context.contentResolver, context.cacheDir)
