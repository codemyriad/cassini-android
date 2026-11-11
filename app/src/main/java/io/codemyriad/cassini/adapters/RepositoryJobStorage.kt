package io.codemyriad.cassini.adapters

import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.NoteLocks
import io.codemyriad.cassini.data.checkpoint.CheckpointRepository
import io.codemyriad.cassini.data.notes.AdoptOutcome
import io.codemyriad.cassini.data.notes.DocumentRepository
import io.codemyriad.cassini.data.notes.NoteRepository
import io.codemyriad.cassini.data.notes.SealedAdopter
import io.codemyriad.cassini.data.notes.knownCaptureStart
import io.codemyriad.cassini.data.settings.SettingsRepository
import io.codemyriad.cassini.engine.job.Adoption
import io.codemyriad.cassini.engine.job.CheckpointLoad
import io.codemyriad.cassini.engine.job.JobStorage
import io.codemyriad.cassini.engine.job.WorkFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import io.codemyriad.cassini.data.checkpoint.CheckpointLoad as StoredCheckpoint

/**
 * [JobStorage] over the `:data` repositories (architecture 2 and 5.4): the note row, the checkpoint and draft names
 * in `notes/<id>/work/`, the sealed output and its adopt. Every function only delegates; the rules live in the
 * repositories.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one function per port function; one repository per area
class RepositoryJobStorage(
    private val paths: AppPaths,
    private val locks: NoteLocks,
    private val notes: NoteRepository,
    private val documents: DocumentRepository,
    private val checkpoints: CheckpointRepository,
    private val settings: SettingsRepository,
    private val adopter: SealedAdopter,
    private val gate: NamingGate,
    private val io: CoroutineDispatcher,
) : JobStorage {
    override suspend fun loadCheckpoint(noteId: String): CheckpointLoad = when (val load = checkpoints.load(noteId)) {
        is StoredCheckpoint.Valid -> CheckpointLoad.Valid(load.state)
        StoredCheckpoint.Missing -> CheckpointLoad.Missing
        is StoredCheckpoint.Mismatch ->
            CheckpointLoad.Mismatch(load.reason.name + load.detail?.let { ": $it" }.orEmpty())
    }

    override fun saveCheckpoint(noteId: String, state: CheckpointState) = checkpoints.saveBlocking(noteId, state)

    /**
     * A reference that holds the current draft names. `NamesStore` keeps its own reference private, so this is a
     * snapshot taken now; the job reads it at once and changes names only through [mutateNames].
     */
    override fun names(noteId: String): AtomicReference<CheckpointNames> =
        AtomicReference(checkpoints.names.currentBlocking(noteId))

    override fun mutateNames(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames =
        checkpoints.names.mutateBlocking(noteId, reducer)

    override fun openNames(noteId: String) = gate.open(noteId)

    override fun closeNames(noteId: String): CheckpointNames {
        gate.close(noteId)
        return checkpoints.names.currentBlocking(noteId)
    }

    override suspend fun audioFile(noteId: String): File = withContext(io) {
        File(paths.note(noteId), row(noteId).audioFile)
    }

    override suspend fun knownDurationMs(noteId: String): Long? = withContext(io) {
        row(noteId).durationMs.takeIf { it > 0 }
    }

    override fun workFiles(noteId: String): WorkFiles {
        checkpoints.openWorkBlocking(noteId)
        return WorkFiles(encodedAudio = checkpoints.audioPart(noteId), clips = checkpoints.clips(noteId))
    }

    override fun sealTarget(noteId: String): File = File(paths.work(noteId), SEALED)

    override fun <T> withNoteLockBlocking(noteId: String, block: () -> T): T = locks.withNoteLockBlocking(noteId, block)

    override fun existingDocumentBlocking(noteId: String): CassiniDocument? = documents.readBlocking(noteId)

    override fun recordedAtUtcBlocking(noteId: String): java.time.Instant? = notes.rowBlocking(noteId)?.knownCaptureStart()

    override fun titleBlocking(noteId: String): String =
        notes.titleBlocking(noteId) ?: throw FileNotFoundException("No note $noteId")

    override fun markSealedBlocking(noteId: String, document: CassiniDocument) =
        documents.markSealedBlocking(noteId, document)

    override fun discardSealedBlocking(noteId: String) {
        if (!checkpoints.dropSealedBlocking(noteId)) throw IOException("Cannot delete the sealed output of $noteId")
    }

    /**
     * [SealedAdopter.of]: the document, the row and the cache, a dirty title, the pending prints with the draft's
     * Remember choices (`VoiceRepository.adoptPending` enrols them, so [Adoption.remembered] needs no second
     * enrolment), then `work/` and the draft names in memory. Then the finalization estimate. Any step that did not
     * finish throws, so the job ends `AdoptPending` and the marker stays for the next try.
     */
    override suspend fun adopt(noteId: String, adoption: Adoption) {
        when (val outcome = adopter.adopt(noteId)) {
            is AdoptOutcome.Adopted, AdoptOutcome.NothingToAdopt -> Unit
            AdoptOutcome.Unreadable, AdoptOutcome.NoNote -> throw IOException("Adopt of $noteId: $outcome")
        }
        if (checkpoints.sealedMarker(noteId)) throw IOException("Adopt of $noteId did not finish; the marker stays")
        gate.open(noteId)
        settings.recordFinalization(adoption.finalizationMs)
    }

    private fun row(noteId: String) = notes.rowBlocking(noteId) ?: throw FileNotFoundException("No note $noteId")

    private companion object {
        /** `work/sealed.opus` (architecture 3.2). */
        const val SEALED = "sealed.opus"
    }
}
