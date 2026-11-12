package io.codemyriad.cassini.ui.theme

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.URLClassLoader

/** Palette initialization must not depend on which screen or test first asks for colours. */
class PaletteInitializationTest {
    @Test fun recordingFirstDoesNotLeaveTheMainPalettesNull() {
        val source = CassiniColors::class.java.protectionDomain.codeSource.location
        val prefix = "io.codemyriad.cassini.ui.theme."
        val parent = javaClass.classLoader
        val loader = object : URLClassLoader(arrayOf(source), parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith(prefix)) return super.loadClass(name, resolve)
                synchronized(this) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }
        }
        loader.use {
            val generated = Class.forName(prefix + "BrandTokenValuesKt", true, loader)
            assertNotNull(generated.getMethod("getCassiniRecordingColors").invoke(null))
            val theme = Class.forName(prefix + "ColorKt", true, loader)
            val light = theme.getMethod("getLightColors").invoke(null)
            val dark = theme.getMethod("getDarkColors").invoke(null)
            assertNotNull("Light palette after Recording initializes first", light)
            assertNotNull("Dark palette after Recording initializes first", dark)
        }
    }
}
