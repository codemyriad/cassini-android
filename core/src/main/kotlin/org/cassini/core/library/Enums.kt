package org.cassini.core.library

// The catalogue enums shared by :data (Room columns) and :engine (job state). Room stores them as TEXT by name,
// never by ordinal, so the declaration order is free but every name is persisted.

/** How a note came to exist. Persisted by name, never rename. */
enum class Origin { RECORDED, IMPORTED_AUDIO, IMPORTED_DOCUMENT, RECOVERED }

/**
 * What the note's audio file is: a capture still being written, plain audio without a transcript, a Cassini
 * document, or a file that cannot be played. Persisted by name, never rename.
 */
enum class AudioKind { LIVE_CAPTURE, PLAIN, DOCUMENT, UNPLAYABLE }

/** The state of a note's transcription job (at most one per note). Persisted by name, never rename. */
enum class JobState { QUEUED, RUNNING, PAUSED, FAILED, NEEDS_CONSENT }

/**
 * What a job does: a first transcription, or a new variant over an existing document. Persisted by name, never
 * rename.
 */
enum class JobKind { TRANSCRIBE, TRANSCRIBE_AGAIN }

/** Why a job is [JobState.PAUSED]. Persisted by name, never rename. */
enum class PauseReason { USER, SYSTEM, MEMORY, INTERRUPTED }

/** A transcript variant: as transcribed, or derived by merging speakers. Persisted by name, never rename. */
enum class VariantKind { TRANSCRIBED, MERGED }
