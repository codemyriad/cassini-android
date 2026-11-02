package org.cassini.core.format

import org.cassini.core.speakers.DefaultLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The cross-app vector `android-written-v1.opus` (coordination `ux-draft/cross-app-vectors`, `expected.json`): a note
 * as Android writes it, made by the engine test `AndroidWrittenVectorTest` with Android's own writers (a transcription
 * with two diarized speakers, a speaker merge as the default version, a rename). The other apps read it in their
 * conformance runs; this test keeps Android reading what it wrote, and pins the shape the other apps must accept:
 * indented JSON bodies, no DATE tag, `und` on every version and body, run-scoped speaker ids, and stored labels shown
 * as stored ("Voce 2" under any UI language, D47).
 */
class AndroidWrittenVectorTest {
    private val bytes = checkNotNull(javaClass.getResourceAsStream("/cross-app/android-written-v1.opus")).use {
        it.readBytes()
    }
    private val document = CassiniDocument.read(bytes)

    private val one = "diar_3f2a9c1e_7b4d_4e21_9a6f_0c8d2b5e1f47_1"
    private val two = "diar_3f2a9c1e_7b4d_4e21_9a6f_0c8d2b5e1f47_2"

    @Test fun theBytesAreThePinnedOnes() {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("29d8a4666d37fe01cee999652470b7c1ba5893af4dbc92a04092005bc777f725", digest)
    }

    @Test fun itOpensOnTheMergedVersion() {
        assertEquals("ok", document.state)
        assertTrue(document.warnings.isEmpty())
        assertEquals(listOf("words", "words-78fd7ee6"), document.variants.map { it.id })
        assertEquals("words-78fd7ee6", document.defaultId)
        val opened = checkNotNull(document.selected(null)?.transcript)
        assertEquals(listOf("Buongiorno", "a", "tutti.", "Grazie,", "iniziamo."), opened.words.map { it.text })
        assertEquals("the merge gave every word to speaker 1", setOf(one), opened.words.map { it.speaker }.toSet())
        val first = checkNotNull(document.selected("words")?.transcript)
        assertEquals(listOf(one, one, one, two, two), first.words.map { it.speaker })
    }

    @Test fun storedLabelsAreShownAsStored() {
        assertEquals("Anna", document.speakerLabel(one))
        assertEquals("Voce 2", document.speakerLabel(two))
        // D47: an Italian placeholder stays the stored label; it is only presented as unnamed.
        assertEquals("Voce 2", DefaultLabels.stored(two, document.speakerLabel(two)))
    }

    @Test fun androidsDeviationsAreInTheFile() {
        for (variant in document.variants) {
            assertEquals(Transcript.UNDETERMINED, variant.descriptor.getString("language"))
            assertEquals(Transcript.UNDETERMINED, variant.transcript!!.language)
            assertTrue("the body is indented JSON", variant.body!!.startsWith("{\n  \""))
        }
        val tags = document.comments.map { it.substringBefore('=').uppercase() }
        assertFalse("no DATE tag", "DATE" in tags)
        val titles = document.comments.filter { it.substringBefore('=').equals("TITLE", ignoreCase = true) }
        assertEquals("Android's own TITLE", listOf("TITLE=Riunione di prova"), titles)
        val provenance = document.manifest!!.getJSONObject("provenance").getJSONObject("speechToText")
        val merged = provenance.getJSONObject("words-78fd7ee6")
        assertEquals(one, merged.getJSONObject("x-speakerMerges").getString(two))
        assertEquals("words", merged.getString("x-derivedFromTranscript"))
        assertTrue(provenance.getJSONObject("words").has("x-speakerDiarization"))
    }
}
