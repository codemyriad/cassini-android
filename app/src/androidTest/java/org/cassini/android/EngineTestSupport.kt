package org.cassini.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.cassini.android.adapters.InstalledEngines
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.engine.asr.ParakeetFiles
import org.cassini.engine.decode.AudioDecoder
import java.io.File

/** The engine's decoder over [context]'s content resolver and cache directory, as the app builds it. */
internal fun audioDecoder(context: Context) = AudioDecoder(context.contentResolver, context.cacheDir)

/**
 * The models the app installed under [filesDir] (where `CassiniTestRunner` keeps the model root, unless the run has
 * `-e emptyModelRoot true`), with the calls of the 0.1.0 model helper that the engine tests were written against.
 */
internal class InstalledModels(private val filesDir: File) {
    fun ready(model: ModelDescriptor): Boolean = model.ready(filesDir)

    /** The absolute path of [name] in [model]'s directory; by default its only file. */
    fun path(model: ModelDescriptor, name: String = model.artifacts.single().name): String = model.path(filesDir, name)

    fun parakeet(): ParakeetFiles = InstalledEngines.parakeet(filesDir)

    fun detector(): String? = InstalledEngines.detector(filesDir)
}

/**
 * With the argument `-e downloadModels true` (scripts/device-smoke.sh gives it), installs the models of [part] that
 * are missing through the graph's ModelRepository, as the app's download does. Blocks; never on the main thread.
 */
internal fun installModelsIfAsked(context: Context, part: ModelPart) {
    if (InstrumentationRegistry.getArguments().getString("downloadModels") != "true") return
    (context.applicationContext as CassiniApplication).graph.models.installBlocking(part)
}
