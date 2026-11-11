# Android Files-app access (`CassiniDocumentsProvider`, D97)

`CassiniDocumentsProvider` (`${applicationId}.documents`) exposes Cassini's note library to the Android system file picker and Files app (`android.content.action.DOCUMENTS_PROVIDER`, decision D97) without adding any IPC, broadcast, or `adb` command surface to release builds.

## Access boundary and threat model

- **Caller restriction (`MANAGE_DOCUMENTS`)**: the provider is declared with `android:exported="true"`, `android:grantUriPermissions="true"`, and `android:permission="android.permission.MANAGE_DOCUMENTS"`. On Android, `MANAGE_DOCUMENTS` is a `signature` platform permission held only by the system document picker (`DocumentsUI` / Files). Third-party apps cannot query or open `CassiniDocumentsProvider` directly; an external app receives a scoped, temporary URI grant only when the user explicitly picks or saves a document through the system UI.
- **No access beyond manual export**: only live (`trashed_at IS NULL`), finished notes are exposed, read-only (`ParcelFileDescriptor.MODE_READ_ONLY`, `Document.COLUMN_FLAGS = 0`):
  - Notes with a sealed Cassini document (`document_state != NONE` and `document.opus` present) expose `document.opus` (`audio/ogg`), containing audio, transcripts, speaker names, title, recording timestamps, and provenance — never voiceprints (`voices/` is never read or referenced). If `title_dirty` is set and no job row exists, `DocumentRepository.retitle(noteId)` flushes the latest title into `document.opus` before `COLUMN_SIZE` or a read descriptor is returned, matching `DocumentRepository.exportCopy`.
  - Plain-audio notes (`document_state == NONE`) expose their original audio file (`capture.opus`, `capture.m4a`, or `source.<ext>`) with its container MIME type.
  - Notes currently being recorded (`NoteOrigin.LIVE_CAPTURE` with `duration_ms == 0`), notes with a running transcription job (`JobState.RUNNING`), and notes with a pending seal (`work/sealed` or `work/sealed.opus` awaiting `AdoptCoordinator`) are omitted from `queryChildDocuments` and refused with `FileNotFoundException` on `queryDocument` / `openDocument` until adopted.
- **Read-only existing notes, import-only creation**:
  - `openDocument` on `note:<uuid>` accepts `"r"` only; `"w"`, `"rw"`, `"wa"`, and `"rwt"` throw `SecurityException`.
  - `deleteDocument`, `removeDocument`, `renameDocument`, `moveDocument`, and `copyDocument` throw `UnsupportedOperationException` (and no `FLAG_SUPPORTS_*` mutation flags are advertised on note rows).
  - The root advertises `Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD` and `Document.FLAG_DIR_SUPPORTS_CREATE`. `createDocument` allocates an unguessable `pending:<uuid>` handle; the first write streams into `cacheDir/provider-imports/<uuid>/<cleanName>` and, on clean close, hands the staged file to `NoticeCenter.imports` (`ImportRepository`), enforcing the 4 GiB size cap, `Limits.MIN_FREE_BYTES` free-space floor, format validation, and SHA-256 `source_sha256` deduplication before deleting the staging directory. Cancelled or failed writes delete staging immediately.
- **Self-URI loop prevention**: `DeepLinks.external` already rejects any `content://` URI whose authority starts with `${ownPackage}.`, so `MainActivity` never re-imports a `CassiniDocumentsProvider` URI as an external deep link.
- **Strict UUID / token path resolution**: note document IDs must parse cleanly through `UUID.fromString` (canonical lowercase round-trip) and resolve exclusively under `AppPaths.note(id)`; pending IDs accept only path-free tokens mapped in memory.

## Threading, memory, and scaling

- `queryRoots` performs no disk or database I/O and returns immediately.
- `queryChildDocuments`, `queryDocument`, and `openDocument` reject main-thread calls (`Looper.getMainLooper().isCurrentThread`), dispatch Room/metadata lookups on `CassiniDispatchers.io`, never touch the `words` table or decode transcripts into memory, and return direct `ParcelFileDescriptor` handles without buffering whole files.
- Display names use `ExportNames.file(title, ext)` with case-insensitive collision numbering (`Title (2).opus`, `Title (3).opus`, ...) computed in chronological (`created_at ASC, id ASC`) order via per-stem suffix counters in `O(N)` time, keeping `queryDocument` and `queryChildDocuments` consistent.
- Room `InvalidationTracker` observation on `notes` and `jobs` calls `ContentResolver.notifyChange` on the child-documents URI so an open Files window updates automatically when notes are imported, sealed/adopted, renamed, or moved to Trash.
