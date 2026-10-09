package org.cassini.android

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit maintenance only: preserves recordings and recovers profiles from names the user already assigned. */
class NamedVoiceRecoveryTest {
    @Test fun recoverProfilesForAnExplicitlyRequestedNote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("recoverNamedNote")
        assumeTrue("Pass recoverNamedNote explicitly to repair that note", !id.isNullOrBlank())
        val context = instrumentation.targetContext
        val app = context.applicationContext as CassiniApplication
        val note = LibraryStore(context.filesDir).load().single { it.id == id }
        val document = CassiniDocument.read(File(note.session.document!!))
        assertEquals("ok", document.state)
        val transcript = document.selected(note.session.selectedVariant)!!.transcript!!
        val defaults = Regex("^(Voce|Voice|Speaker) \\d+$")
        val named = transcript.words.map { it.speaker }.distinct().mapNotNull { speaker ->
            document.speakerLabel(speaker)?.takeIf { it.isNotBlank() && it != speaker && !defaults.matches(it) }?.let { speaker to it }
        }.toMap()
        assertTrue("The requested note must contain names", named.isNotEmpty())
        val slots = named.keys.withIndex().associate { it.value to it.index }
        val turns = mutableListOf<SpeakerTurn>()
        for (word in transcript.words) {
            val slot = slots[word.speaker] ?: continue
            val last = turns.lastOrNull()
            if (last != null && last.speaker == slot && word.startMs <= last.endMs + 500)
                turns[turns.lastIndex] = last.copy(endMs = maxOf(last.endMs, word.endMs))
            else turns += SpeakerTurn(word.startMs, word.endMs, slot)
        }
        val model = VoiceprintModel.inFiles(context.filesDir)
        assumeTrue(model.ready())
        val pcm = File.createTempFile("voice-recovery-", ".pcm", context.cacheDir)
        val computed = PcmFile(pcm).use { audio ->
            AudioDecoder.stream(context, Uri.parse(note.session.uri), audio::append)
            val selected = Voiceprints.select(turns, transcript.words, audio.sampleRate, audio.sampleCount)
            Voiceprints.compute(File(model.modelPath), audio, selected) { }
        }
        assertTrue("Each named speaker must have enough clean speech", computed.keys.containsAll(named.keys))
        val originalPrints = app.noteVoices.load(document.manifest!!.getJSONObject("meeting").getString("id"),
            note.session.selectedVariant ?: document.defaultId!!)
        val confirmed = computed.mapValues { (speaker, print) ->
            val name = named.getValue(speaker)
            val existing = app.voices.load().firstOrNull { it.name.equals(name, true) }
            val voice = if (existing != null && originalPrints[speaker]?.match?.let { it.state == Match.State.CONFIRMED && it.voiceId == existing.id } == true)
                existing else app.voices.enrol(existing?.id, name, VoiceprintModel.model.sha256, print.first, print.second, System.currentTimeMillis())
            SpeakerPrint(print.first, print.second, Match(voice.id, 1f, Match.State.CONFIRMED))
        }
        app.noteVoices.merge(document.manifest!!.getJSONObject("meeting").getString("id"),
            note.session.selectedVariant ?: document.defaultId!!, VoiceprintModel.model.sha256, confirmed)
        val applyTo = InstrumentationRegistry.getArguments().getString("applyNamedProfilesToNote")
        if (!applyTo.isNullOrBlank()) {
            val target = LibraryStore(context.filesDir).load().single { it.id == applyTo }
            val originalFile = File(target.session.document!!)
            val targetDocument = CassiniDocument.read(originalFile)
            val meeting = targetDocument.manifest!!.getJSONObject("meeting").getString("id")
            val variant = target.session.selectedVariant ?: targetDocument.defaultId!!
            val prints = app.noteVoices.load(meeting, variant)
            val voices = app.voices.load()
            val matches = VoiceMatcher.match(prints.mapValues { it.value.embedding }, voices, VoiceprintModel.model.sha256,
                Voiceprints.THRESHOLDS.getValue(VoiceprintModel.model.sha256))
            matches.forEach { (speaker, match) -> app.noteVoices.setMatch(meeting, variant, speaker, match) }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Existing meeting matches: ${matches.map { (speaker, match) -> "$speaker ${voices.first { it.id == match.voiceId }.name} ${match.state} ${match.score}" }.joinToString()}\n")
            })
            val labels = matches.filterValues { it.state == Match.State.AUTO }.mapNotNull { (speaker, match) ->
                val oldName = targetDocument.speakerLabel(speaker)
                if (oldName == speaker || oldName == null || defaults.matches(oldName)) voices.firstOrNull { it.id == match.voiceId }?.let { speaker to it.name }
                else null
            }.toMap()
            if (labels.isNotEmpty()) {
                val (file, renamed) = DocumentStore(context).relabel(originalFile, targetDocument, labels)
                val next = target.session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                    transcript = renamed.selected(variant)!!.transcript)
                LibraryStore(context.filesDir).save(next)
                val session = SessionStore(context.filesDir).load()
                if (session.libraryId == target.id) SessionStore(context.filesDir).save(next.copy(screen = session.screen))
                matches.filterValues { it.state == Match.State.AUTO }.forEach { (speaker, match) -> app.noteVoices.setMatch(meeting, variant, speaker, match) }
                assertEquals(OggOpus.scan(originalFile).digest(), OggOpus.scan(file).digest())
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Applied remembered names: ${labels.values.joinToString()}\n") })
            }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Recovered named voices: ${named.values.joinToString()}\n")
        })
    }
}
