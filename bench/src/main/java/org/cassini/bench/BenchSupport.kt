package org.cassini.bench

import android.content.Context
import org.cassini.android.adapters.InstalledEngines
import org.cassini.core.models.ModelDescriptor
import org.cassini.engine.asr.ParakeetFiles
import org.cassini.engine.decode.AudioDecoder
import java.io.File

/** The engine's decoder over [context]'s content resolver and cache directory, as the app builds it. */
internal fun audioDecoder(context: Context) = AudioDecoder(context.contentResolver, context.cacheDir)

/**
 * The models the app installed under [filesDir], with the calls of the 0.1.0 model helper that the bench tests were
 * written against. The bench never downloads a model: install the models in the app first.
 */
internal class InstalledModels(private val filesDir: File) {
    fun ready(model: ModelDescriptor): Boolean = model.ready(filesDir)

    /** The absolute path of [name] in [model]'s directory; by default its only file. */
    fun path(model: ModelDescriptor, name: String = model.artifacts.single().name): String = model.path(filesDir, name)

    fun parakeet(): ParakeetFiles = InstalledEngines.parakeet(filesDir)
}
