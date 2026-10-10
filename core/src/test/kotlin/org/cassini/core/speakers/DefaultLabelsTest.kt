package org.cassini.core.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultLabelsTest {
    /** `ui_speaker_label` in every values directory of :ui, up to its number placeholder. */
    private fun resourcePrefixes(): Set<String> {
        val res = File(System.getProperty("cassini.appResources", "../ui/src/main/res")!!)
        val files = res.listFiles().orEmpty().filter {
            it.name.startsWith("values")
        }.map { File(it, "strings.xml") }.filter { it.isFile }
        assertTrue("No string resources under $res", files.isNotEmpty())
        val pattern = Regex("""<string name="ui_speaker_label">([^<]*)</string>""")
        return files.mapNotNull { pattern.find(it.readText())?.groupValues?.get(1) }.map {
            assertTrue("ui_speaker_label must end with its number: $it", it.endsWith("%1\$d"))
            it.removeSuffix("%1\$d")
        }.toSet()
    }

    @Test fun thePrefixesAreTheStringResources() {
        assertEquals(resourcePrefixes(), DefaultLabels.PREFIXES.toSet())
    }

    @Test fun placeholdersInEveryLanguageAreDefault() {
        assertTrue(DefaultLabels.isDefault("spk_1", "Speaker 1"))
        assertTrue(DefaultLabels.isDefault("spk_1", "Speaker 12"))
        assertTrue(DefaultLabels.isDefault("spk_1", "Voce 2"))
        assertTrue(DefaultLabels.isDefault("diar_x_1", "diar_x_1"))
        assertTrue(DefaultLabels.isDefault("spk_1", ""))
        assertTrue(DefaultLabels.isDefault("spk_1", "  "))
    }

    @Test fun namesAreNotDefault() {
        assertFalse(DefaultLabels.isDefault("spk_1", "Ada Example"))
        assertFalse(DefaultLabels.isDefault("spk_1", "Speaker Ada"))
        assertFalse(DefaultLabels.isDefault("spk_1", "Speaker"))
        assertFalse(DefaultLabels.isDefault("spk_1", "Voce"))
        assertFalse(DefaultLabels.isDefault("spk_1", "speaker 1"))
        assertFalse(DefaultLabels.isDefault("spk_1", "Relatore 1"))
    }
}
