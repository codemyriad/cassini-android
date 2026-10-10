package org.cassini.data.voices

import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceCodec
import org.cassini.core.speakers.VoiceStaleness
import org.cassini.core.speakers.Voiceprints
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteIds
import org.cassini.data.Threads
import org.cassini.data.checkpoint.NamesStore
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.NoteVoiceLinkEntity
import org.cassini.data.db.VoiceNoteCount
import org.cassini.data.notes.NoteFilesCleaner
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Every voiceprint and voice match, all under `voices/` (docs/architecture.md, sections 3.2 and 4.5): `people.json`
 * (VoiceCodec version 1, the saved people), `generation` (bumped by "Forget everyone"), `notes/<noteId>.json` (the
 * sealed prints and matches of a note, NoteVoiceCodec version 2) and `pending/<noteId>.json` (the prints of a sealed
 * job awaiting adopt). Room's `note_voice_links` mirrors the per-note files without embeddings; every write of a
 * per-note file rewrites that note's links under the same lock.
 *
 * Each file has its own [ReentrantLock], shared by suspend callers (inside `withContext(io)`) and the inference
 * thread, never held across a suspension point. When one call holds several, it takes them in this order:
 * `generation`, `people.json`, `pending/<id>.json`, `notes/<id>.json`. Draft names are changed through [NamesStore]
 * only after every voice lock is released, so a voice lock is never held while a note lock is taken.
 *
 * A file whose `version` is newer than its codec stays in place and is never written ([VoiceProblem.NEWER]); a
 * file that does not parse is renamed `<name>.corrupt-<epoch ms>` and reads as empty ([VoiceProblem.DAMAGED]).
 */
// One store with one API, as architecture 4.5 lists it; each entry point is a few lines over the locked helpers.
@Suppress("TooManyFunctions")
class VoiceRepository(
    database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val names: NamesStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : NoteFilesCleaner {
    private val links = database.voiceLinks()
    private val notes = database.notes()
    private val files = VoiceFiles(clock)
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** `people.json` as last read or written; null until the first use. */
    private val book = MutableStateFlow<Book?>(null)

    private data class Book(val voices: List<Voice>, val problem: VoiceProblem?) {
        val readOnly: Boolean get() = problem == VoiceProblem.NEWER || problem == VoiceProblem.UNREADABLE
    }

    // Saved people.

    /** The Saved voices list with note counts and the stale flag, updated after every change. */
    fun people(): Flow<SavedVoices> = flow {
        locked(paths.people) { peopleLocked() }
        emitAll(combine(book.filterNotNull(), notes.voiceNoteCounts(), ::savedVoices))
    }.flowOn(dispatchers.io)

    /** The saved people, for the job's live matching. Empty when `people.json` cannot be used. */
    @WorkerThread
    fun peopleBlocking(): List<Voice> {
        Threads.assertOffMain("VoiceRepository.peopleBlocking")
        return locked(paths.people) { peopleLocked().voices }
    }

    /** The current voice generation, for the job: its pending prints carry it to adopt. */
    @WorkerThread
    fun generationBlocking(): Long {
        Threads.assertOffMain("VoiceRepository.generationBlocking")
        return locked(paths.generation) { files.readGeneration(paths.generation) }
    }

    suspend fun generation(): Long = withContext(dispatchers.io) { generationBlocking() }

    /** Renames a saved person. Names already written into notes stay as they are. */
    suspend fun rename(voiceId: String, name: String): VoiceEdit {
        val clean = name.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH } ?: return VoiceEdit.Invalid
        return writing(VoiceEdit.Failed(Failure.SAVE)) {
            locked(paths.people) {
                val current = peopleLocked()
                when {
                    current.readOnly -> VoiceEdit.ReadOnly
                    current.voices.none { it.id == voiceId } -> VoiceEdit.NotFound
                    else -> {
                        savePeopleLocked(current.voices.map { if (it.id == voiceId) it.copy(name = clean) else it })
                        VoiceEdit.Done
                    }
                }
            }
        }
    }

    /**
     * Forgets one person: removes them from `people.json`, removes their matches from every sealed and pending
     * per-note file (and so from the links), and from the live matches, Remember choices and automatic names of every
     * draft. Names already in notes stay. The matches go before the person, so a failed write leaves the person
     * saved and Forget can run again.
     */
    suspend fun forget(voiceId: String): VoiceEdit {
        val edit = writing(VoiceEdit.Failed(Failure.SAVE)) {
            locked(paths.people) {
                val current = peopleLocked()
                when {
                    current.readOnly -> VoiceEdit.ReadOnly
                    current.voices.none { it.id == voiceId } -> VoiceEdit.NotFound
                    else -> {
                        scrubLocked(voiceId)
                        savePeopleLocked(current.voices.filter { it.id != voiceId })
                        VoiceEdit.Done
                    }
                }
            }
        }
        if (edit == VoiceEdit.Done) names.mutateAll { forgetInDraft(it, voiceId) }
        return edit
    }

    /**
     * "Forget everyone": writes `generation` = old + 1 first, then deletes everything else under `voices/` (people,
     * sealed and pending prints, set-aside copies), clears every link, and clears the live matches, Remember choices
     * and automatic names of every draft. A running job's pending prints carry the old generation, so its adopt
     * keeps no print and no match.
     */
    suspend fun forgetEveryone(): VoiceEdit {
        var next: Long? = null
        val edit = writing<VoiceEdit>(VoiceEdit.Failed(Failure.SAVE)) {
            next = locked(paths.generation) { locked(paths.people) { forgetEveryoneLocked() } }
            if (next == null) VoiceEdit.ReadOnly else VoiceEdit.Done
        }
        next?.let { generation ->
            names.mutateAll { draft ->
                draft.copy(
                    remembered = emptyMap(),
                    automatic = emptyMap(),
                    live = emptyMap(),
                    rejected = emptyMap(),
                    generation = generation,
                )
            }
        }
        return edit
    }

    // The job and adopt.

    /**
     * Writes the final prints of a sealed job to `voices/pending/<noteId>.json`, before the job writes its sealed
     * marker. [prints] are keyed `<variantId>/<speakerId>` for the sealed document's variant; [generation] is the
     * one the job matched against ([generationBlocking]). The draft's Remember choices for these speakers are stored
     * with them, so adopt can enrol them even after `work/` is gone. Returns false when the file could not be written
     * (voiceprints are best effort: the transcript does not depend on them).
     */
    @WorkerThread
    fun savePendingBlocking(noteId: String, prints: Map<String, SpeakerPrint>, generation: Long): Boolean {
        Threads.assertOffMain("VoiceRepository.savePendingBlocking")
        val file = pendingFile(noteId)
        val draft = names.currentBlocking(noteId)
        val choices = if (draft.generation == generation) confirmations(draft, prints.keys) else emptyMap()
        return try {
            locked(file) { files.writePending(file, PendingPrints(MODEL, prints, generation, choices)) }
            true
        } catch (error: IOException) {
            Log.w(TAG, "Cannot write the pending prints of $noteId", error)
            false
        }
    }

    /**
     * Finishes the voice part of an adopt, after `adoptSealed`. When the pending file's generation is the current
     * one, its prints replace that variant's prints in `voices/notes/<noteId>.json` (matches to voices that no longer
     * exist are dropped), the draft's Remember choices whose voice still exists (or that make a new person) are
     * enrolled, and the links are rewritten. When the generation changed, nothing is kept. Then the pending file is
     * deleted. Running it again does nothing.
     */
    suspend fun adoptPending(noteId: String): PendingOutcome {
        val pending = pendingFile(noteId)
        val sealed = notesFile(noteId)
        return withContext(dispatchers.io) {
            locked(paths.generation) {
                locked(paths.people) { locked(pending) { locked(sealed) { adoptLocked(noteId) } } }
            }
        }
    }

    /**
     * Rebuilds `note_voice_links` from every `voices/notes/<noteId>.json` at start (architecture 4.8 item 4), and deletes the
     * sealed and pending voice files of notes that have no row. A newer or unreadable file stays and gives no links;
     * a damaged one is renamed.
     */
    suspend fun syncLinks(): LinkSync {
        val rows = notes.ids().toHashSet()
        val (found, sync) = withContext(dispatchers.io) { readAllLocked(rows) }
        links.replaceAll(found)
        return sync
    }

    /** Deletes both voice files of a note and its links (step 2 of the purge, architecture 4.10). */
    override suspend fun deleteForNote(noteId: String): Boolean {
        val pending = pendingFile(noteId)
        val sealed = notesFile(noteId)
        val deleted = withContext(dispatchers.io) {
            locked(pending) {
                locked(sealed) {
                    val ok = (leftovers(pending) + leftovers(sealed)).all { !it.exists() || it.delete() }
                    links.deleteForNoteBlocking(noteId)
                    ok
                }
            }
        }
        names.release(noteId)
        return deleted
    }

    // A sealed note's prints, for the Note screen.

    /** The prints of [variantId] of [noteId] by speaker id; empty without a readable file. */
    suspend fun prints(noteId: String, variantId: String): Map<String, SpeakerPrint> {
        val file = notesFile(noteId)
        val prefix = NoteVoiceCodec.prefix(variantId)
        return withContext(dispatchers.io) {
            locked(file) { (files.readNote(file) as? FileRead.Loaded)?.value?.prints.orEmpty() }
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
        }
    }

    /** Sets (or with null clears) the match of one speaker print. False when there is no such print to change. */
    suspend fun setMatch(noteId: String, variantId: String, speakerId: String, match: Match?): Boolean {
        val key = NoteVoiceCodec.prefix(variantId) + speakerId
        return editNote(noteId) { prints ->
            prints[key]?.let { prints + (key to it.copy(match = match)) }
        }
    }

    /**
     * Copies the prints of [fromVariant] to [toVariant] after a speaker merge, leaving out [without] (the merged-away
     * speaker). A CONFIRMED match is copied as AUTO: one enrolment belongs to one variant, so taking the copy back
     * never unenrols twice. False when there was nothing to copy.
     */
    suspend fun copyPrints(noteId: String, fromVariant: String, toVariant: String, without: String?): Boolean {
        val from = NoteVoiceCodec.prefix(fromVariant)
        val to = NoteVoiceCodec.prefix(toVariant)
        return editNote(noteId) { prints ->
            val copies = prints.filterKeys { it.startsWith(from) && it.removePrefix(from) != without }
                .map { (key, print) -> to + key.removePrefix(from) to print.copy(match = print.match?.asAuto()) }
            if (copies.isEmpty()) null else prints.filterKeys { !it.startsWith(to) } + copies
        }
    }

    /**
     * "Remember this voice" on a sealed note: enrols the print of [speakerId] into [voiceId] (a new person when null
     * or unknown) called [name], and marks the print CONFIRMED. Returns the saved person, or null when there is no
     * print, `people.json` is read-only or a file could not be written.
     */
    suspend fun enrol(noteId: String, variantId: String, speakerId: String, voiceId: String?, name: String): Voice? {
        val file = notesFile(noteId)
        val key = NoteVoiceCodec.prefix(variantId) + speakerId
        return writing(null) {
            locked(paths.people) { locked(file) { enrolLocked(noteId, key, voiceId, name.trim()) } }
        }
    }

    /**
     * Takes back the enrolment of a CONFIRMED print ("Not this person" on a confirmed name) and clears its match. A
     * person with only this print is forgotten everywhere, as [forget] does.
     */
    suspend fun unenrol(noteId: String, variantId: String, speakerId: String): UnenrolOutcome {
        val file = notesFile(noteId)
        val key = NoteVoiceCodec.prefix(variantId) + speakerId
        val (outcome, forgotten) = writing(UnenrolOutcome.Failed(Failure.SAVE) to null) {
            locked(paths.people) {
                // The scrub takes pending locks, so it runs after the note's file lock is released.
                locked(file) { unenrolLocked(noteId, key) }.also { (_, gone) -> gone?.let(::scrubLocked) }
            }
        }
        if (forgotten != null) names.mutateAll { forgetInDraft(it, forgotten) }
        return outcome
    }

    // Locked helpers. Each runs with the locks its caller took, in the documented order.

    /** Runs [block] on IO; an I/O error while it writes a voice file gives [failed] (architecture 4, shared rules). */
    private suspend fun <T> writing(failed: T, block: () -> T): T = withContext(dispatchers.io) {
        try {
            block()
        } catch (error: IOException) {
            Log.w(TAG, "Cannot write a voice file", error)
            failed
        }
    }

    /** `people.json` as cached; a file that could not be read is read again, as the error may have passed. */
    private fun peopleLocked(): Book =
        book.value?.takeIf { it.problem != VoiceProblem.UNREADABLE } ?: readPeopleLocked().also { book.value = it }

    private fun readPeopleLocked(): Book = when (val read = files.readPeople(paths.people)) {
        FileRead.Missing -> Book(emptyList(), null)
        is FileRead.Loaded -> Book(read.value, null)
        is FileRead.Kept -> Book(emptyList(), read.problem)
        is FileRead.Damaged -> Book(emptyList(), VoiceProblem.DAMAGED)
    }

    private fun savePeopleLocked(voices: List<Voice>) {
        files.writePeople(paths.people, voices)
        book.value = Book(voices, null)
    }

    private fun forgetEveryoneLocked(): Long? {
        if (peopleLocked().readOnly) return null
        val next = files.readGeneration(paths.generation) + 1
        files.writeGeneration(paths.generation, next)
        for (entry in paths.voices.listFiles().orEmpty()) {
            when {
                entry.name.startsWith(paths.generation.name) -> Unit
                entry.isDirectory -> {
                    entry.listFiles().orEmpty().forEach { file -> locked(baseOf(file)) { file.deleteRecursively() } }
                    entry.deleteRecursively()
                }
                else -> entry.delete()
            }
        }
        links.clearBlocking()
        book.value = Book(emptyList(), null)
        return next
    }

    /** Removes the matches to [voiceId] from every pending and sealed per-note file. */
    private fun scrubLocked(voiceId: String) {
        for (file in jsonFiles(File(paths.voices, PENDING))) {
            locked(file) {
                val read = files.readPending(file) as? FileRead.Loaded ?: return@locked
                val pending = read.value
                val prints = withoutVoice(pending.prints, voiceId)
                val choices = pending.confirmations.filterValues { it.isNew || it.voiceId != voiceId }
                if (prints != pending.prints || choices != pending.confirmations) {
                    files.writePending(file, pending.copy(prints = prints, confirmations = choices))
                }
            }
        }
        for (file in jsonFiles(File(paths.voices, NOTES))) {
            locked(file) {
                val read = files.readNote(file) as? FileRead.Loaded ?: return@locked
                val prints = withoutVoice(read.value.prints, voiceId)
                if (prints != read.value.prints) writeNoteLocked(file.name.removeSuffix(JSON), read.value.model, prints)
            }
        }
    }

    private fun adoptLocked(noteId: String): PendingOutcome {
        val file = pendingFile(noteId)
        return when (val read = files.readPending(file)) {
            FileRead.Missing -> PendingOutcome.NothingPending
            is FileRead.Kept -> PendingOutcome.Kept(read.problem)
            // A damaged pending file was set aside; there is nothing left to merge.
            is FileRead.Damaged -> PendingOutcome.NothingPending
            is FileRead.Loaded -> {
                val outcome = when {
                    !links.hasNoteBlocking(noteId) -> PendingOutcome.NoNote
                    read.value.generation != files.readGeneration(paths.generation) -> PendingOutcome.Discarded
                    else -> mergeLocked(noteId, read.value)
                }
                if (outcome !is PendingOutcome.Kept) files.delete(file)
                outcome
            }
        }
    }

    private fun mergeLocked(noteId: String, pending: PendingPrints): PendingOutcome {
        val existing = when (val read = files.readNote(notesFile(noteId))) {
            is FileRead.Kept -> return PendingOutcome.Kept(read.problem)
            is FileRead.Loaded -> read.value
            FileRead.Missing, is FileRead.Damaged -> null
        }
        val people = peopleLocked()
        val known = people.voices.mapTo(HashSet()) { it.id }
        // Without a usable people.json nothing can be checked, so the matches stay as the job made them.
        val cleaned = pending.prints.mapValues { (_, print) ->
            val gone = !people.readOnly && print.match != null && print.match?.voiceId !in known
            if (gone) print.copy(match = null) else print
        }
        val dropped = cleaned.count { (key, print) ->
            print.match == null && pending.prints.getValue(key).match != null
        }
        val variants = cleaned.keys.mapTo(HashSet()) { it.substringBefore('/') + "/" }
        // A file made with another voiceprint model is replaced (0.1.0 rule); other variants' prints stay.
        val previous = existing?.takeIf { it.model == pending.model }?.prints.orEmpty()
        val kept = previous.filterKeys { key -> variants.none { key.startsWith(it) } }
        val enrolment = enrolChoices(people, cleaned, previous, pending.confirmations)
        if (enrolment.enrolled.isNotEmpty()) savePeopleLocked(enrolment.voices)
        val merged = kept + cleaned + enrolment.prints
        if (merged.isNotEmpty() || existing != null) writeNoteLocked(noteId, pending.model, merged)
        return PendingOutcome.Merged(cleaned.size, dropped, enrolment.enrolled)
    }

    private class Enrolment(val voices: List<Voice>, val enrolled: List<String>, val prints: Map<String, SpeakerPrint>)

    /**
     * Enrols the draft's Remember [choices] for the pending [prints] whose voice still exists, or that make a new
     * person. A choice whose print already has that CONFIRMED match in the [previous] file was enrolled by an earlier
     * run of this adopt (a crash before the pending file was deleted): it keeps that match and is not enrolled twice.
     * A crash between the `people.json` write and the note file write can still count one print twice; it never
     * makes a second person, because a new person's id is reserved when the pending file is written.
     */
    private fun enrolChoices(
        people: Book,
        prints: Map<String, SpeakerPrint>,
        previous: Map<String, SpeakerPrint>,
        choices: Map<String, Confirmation>,
    ): Enrolment {
        if (people.readOnly) return Enrolment(people.voices, emptyList(), emptyMap())
        var voices = people.voices
        val enrolled = mutableListOf<String>()
        val confirmed = LinkedHashMap<String, SpeakerPrint>()
        for ((speaker, choice) in choices) {
            val key = prints.keys.firstOrNull { it.substringAfter('/') == speaker }
            val print = key?.let(prints::get)
            val earlier = key?.let(previous::get)?.match
                ?.takeIf { it.state == Match.State.CONFIRMED && it.voiceId == choice.voiceId }
            val exists = voices.any { it.id == choice.voiceId }
            val result = when {
                key == null || print == null -> null
                earlier != null -> {
                    if (exists) confirmed[key] = print.copy(match = earlier)
                    null
                }
                !exists && !choice.isNew -> null
                else -> enrolOne(voices, choice.voiceId, choice.name, print)
            }
            if (result != null && key != null && print != null) {
                voices = result.first
                enrolled += result.second.voiceId
                confirmed[key] = print.copy(match = result.second)
            }
        }
        return Enrolment(voices, enrolled, confirmed)
    }

    /**
     * [voices] with [print] enrolled into [voiceId] (a new person with that id when it is not saved), and the
     * CONFIRMED match for the print; null for a print that cannot be enrolled (a zero vector).
     */
    private fun enrolOne(
        voices: List<Voice>,
        voiceId: String,
        name: String,
        print: SpeakerPrint,
    ): Pair<List<Voice>, Match>? {
        val unit = Voiceprints.normalised(print.embedding)
        if (unit == null || name.isEmpty()) return null
        val previous = voices.firstOrNull { it.id == voiceId }
        val score = previous?.takeIf { it.mean.size == unit.size }?.let { Voiceprints.cosine(unit, it.mean) } ?: 1f
        val (enrolled, voice) =
            VoiceCodec.enrol(voices, previous?.id, name, MODEL, print.embedding, print.seconds, clock())
        // VoiceCodec names a new person at random; keep the id that was reserved for them.
        val next = if (previous != null) {
            enrolled
        } else {
            enrolled.map { if (it.id == voice.id) it.copy(id = voiceId) else it }
        }
        return next to Match(voiceId, score, Match.State.CONFIRMED)
    }

    private fun enrolLocked(noteId: String, key: String, voiceId: String?, name: String): Voice? {
        val people = peopleLocked()
        val read = (files.readNote(notesFile(noteId)) as? FileRead.Loaded)?.value?.takeUnless { people.readOnly }
        val print = read?.prints?.get(key)
        val target = voiceId?.takeIf { id -> people.voices.any { it.id == id } } ?: newVoiceId()
        val result = if (read != null && print != null) enrolOne(people.voices, target, name, print) else null
        if (result != null && read != null && print != null) {
            savePeopleLocked(result.first)
            writeNoteLocked(noteId, read.model, read.prints + (key to print.copy(match = result.second)))
        }
        return result?.first?.firstOrNull { it.id == target }
    }

    /** The outcome, and the id of a person forgotten because this was their only print. */
    private fun unenrolLocked(noteId: String, key: String): Pair<UnenrolOutcome, String?> {
        val people = peopleLocked()
        val read = files.readNote(notesFile(noteId)) as? FileRead.Loaded
        val print = read?.value?.prints?.get(key)
        val match = print?.match?.takeIf { it.state == Match.State.CONFIRMED }
        val voice = people.voices.firstOrNull { it.id == match?.voiceId }
        return when {
            people.readOnly -> UnenrolOutcome.ReadOnly to null
            read == null || print == null || match == null -> UnenrolOutcome.NothingToUndo to null
            voice == null || voice.count <= 1 -> {
                writeNoteLocked(noteId, read.value.model, read.value.prints + (key to print.copy(match = null)))
                if (voice != null) savePeopleLocked(people.voices.filter { it.id != voice.id })
                UnenrolOutcome.Forgotten to voice?.id
            }
            else -> {
                val undone = VoiceCodec.unenrol(voice, print.embedding) ?: voice
                savePeopleLocked(people.voices.map { if (it.id == voice.id) undone else it })
                writeNoteLocked(noteId, read.value.model, read.value.prints + (key to print.copy(match = null)))
                UnenrolOutcome.Unenrolled to null
            }
        }
    }

    /** Applies [change] to the sealed prints of [noteId]; a null result means nothing to write. */
    private suspend fun editNote(
        noteId: String,
        change: (Map<String, SpeakerPrint>) -> Map<String, SpeakerPrint>?,
    ): Boolean {
        val file = notesFile(noteId)
        return withContext(dispatchers.io) {
            locked(file) {
                val read = files.readNote(file) as? FileRead.Loaded
                val next = read?.let { change(it.value.prints) }
                if (read != null && next != null) writeNoteLocked(noteId, read.value.model, next)
                next != null
            }
        }
    }

    /** Writes `voices/notes/<noteId>.json` and rewrites the note's links; the caller holds the file's lock. */
    private fun writeNoteLocked(noteId: String, model: String, prints: Map<String, SpeakerPrint>) {
        files.writeNote(notesFile(noteId), NotePrints(model, prints))
        links.replaceForNoteBlocking(noteId, VoiceLinks.of(noteId, prints))
    }

    private fun readAllLocked(rows: Set<String>): Pair<List<NoteVoiceLinkEntity>, LinkSync> {
        val found = mutableListOf<NoteVoiceLinkEntity>()
        val deleted = mutableListOf<String>()
        val problems = mutableMapOf<String, VoiceProblem>()
        for (file in jsonFiles(File(paths.voices, NOTES))) {
            val noteId = file.name.removeSuffix(JSON)
            locked(file) {
                if (noteId !in rows) {
                    if (leftovers(file).all { !it.exists() || it.delete() }) deleted += noteId
                    return@locked
                }
                when (val read = files.readNote(file)) {
                    is FileRead.Loaded -> found += VoiceLinks.of(noteId, read.value.prints)
                    is FileRead.Kept -> problems[noteId] = read.problem
                    is FileRead.Damaged -> problems[noteId] = VoiceProblem.DAMAGED
                    FileRead.Missing -> Unit
                }
            }
        }
        for (file in jsonFiles(File(paths.voices, PENDING))) {
            val noteId = file.name.removeSuffix(JSON)
            if (noteId !in rows) locked(file) { leftovers(file).forEach { it.delete() } }
        }
        return found to LinkSync(found.size, deleted, problems)
    }

    private fun savedVoices(book: Book, counts: List<VoiceNoteCount>): SavedVoices {
        val byVoice = counts.associate { it.voiceId to it.notes }
        val rows = book.voices.map { SavedVoice(it, byVoice[it.id] ?: 0, VoiceStaleness.isStale(it)) }
            .sortedWith(SAVED_ORDER)
        return SavedVoices(rows, book.problem)
    }

    // Files and locks.

    private fun notesFile(noteId: String): File = File(File(paths.voices, NOTES), fileName(noteId))

    private fun pendingFile(noteId: String): File = File(File(paths.voices, PENDING), fileName(noteId))

    /** The note-id path guard: a canonical UUID, and NoteVoiceCodec's own file-name check. */
    private fun fileName(noteId: String): String {
        require(NoteIds.isValid(noteId)) { "Not a note id: $noteId" }
        return NoteVoiceCodec.fileName(noteId)
    }

    /** The per-note files in [dir] named by a valid note id. */
    private fun jsonFiles(dir: File): List<File> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(JSON) && NoteIds.isValid(it.name.removeSuffix(JSON)) }
        .sortedBy { it.name }

    /** [file] and every file beside it that starts with its name (`.bak`, `.new`, `.corrupt-…`). */
    private fun leftovers(file: File): List<File> =
        (file.parentFile?.listFiles().orEmpty().filter { it.name.startsWith(file.name) } + file).distinct()

    /** The file whose lock covers [file]: `x.json.bak` and `x.json.corrupt-1` belong to `x.json`. */
    private fun baseOf(file: File): File =
        if (JSON in file.name) File(file.parentFile, file.name.substringBefore(JSON) + JSON) else file

    private fun <T> locked(file: File, block: () -> T): T =
        fileLocks.computeIfAbsent(file.absolutePath) { ReentrantLock() }.withLock(block)

    /** What [syncLinks] did. */
    data class LinkSync(
        val links: Int,
        /** Note ids whose voice files were deleted because the note has no row. */
        val deleted: List<String>,
        /** Note ids whose sealed voice file could not be used. */
        val problems: Map<String, VoiceProblem>,
    )

    companion object {
        /** The longest name of a saved person (features.md section 10, Rename). */
        const val MAX_NAME_LENGTH = 80
        private val MODEL = ModelCatalog.voiceprintModel.sha256
        private val SAVED_ORDER = compareByDescending<SavedVoice> { it.notes }
            .thenByDescending { it.confirmedSeconds }
            .thenBy { it.name }
        private const val NOTES = "notes"
        private const val PENDING = "pending"
        private const val JSON = ".json"
        private const val TAG = "VoiceRepository"

        private fun newVoiceId() = "voice_${UUID.randomUUID()}"

        private fun Match.asAuto(): Match = if (state == Match.State.CONFIRMED) copy(state = Match.State.AUTO) else this

        private fun withoutVoice(prints: Map<String, SpeakerPrint>, voiceId: String) = prints.mapValues { (_, print) ->
            if (print.match?.voiceId == voiceId) print.copy(match = null) else print
        }

        /** The draft's Remember choices for the speakers of [keys] that have a name; new people get an id now. */
        private fun confirmations(draft: CheckpointNames, keys: Set<String>): Map<String, Confirmation> {
            val speakers = keys.mapTo(HashSet()) { it.substringAfter('/') }
            return draft.remembered.filterKeys { it in speakers }.mapNotNull { (speaker, voiceId) ->
                draft.labels[speaker]?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    val isNew = voiceId.isEmpty()
                    speaker to Confirmation(if (isNew) newVoiceId() else voiceId, name, isNew)
                }
            }.toMap()
        }

        /** [draft] without anything that names [voiceId]: live matches, automatic names, Remember choices. */
        private fun forgetInDraft(draft: CheckpointNames, voiceId: String): CheckpointNames {
            val speakers = draft.live.filterValues { it.voiceId == voiceId }.keys
            return draft.copy(
                live = draft.live - speakers,
                automatic = draft.automatic - speakers,
                remembered = draft.remembered.filterValues { it != voiceId },
                rejected = draft.rejected.mapValues { it.value - voiceId }.filterValues { it.isNotEmpty() },
            )
        }
    }
}
