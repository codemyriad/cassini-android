# Android Files access (Task C)

The provider is independent of the bulk-transfer draft. Task A format/repository files are unchanged.

- Exported DocumentsProvider at ${applicationId}.documents, protected by android.permission.MANAGE_DOCUMENTS and grantUriPermissions. Standard DOCUMENTS_PROVIDER filter only. No adb, broadcast or command endpoint.
- Root metadata is quick and does not initialize the graph from provider.onCreate (Android creates providers before Application.onCreate). Binder callbacks initialize/read the catalogue on IO.
- List live Cassini .opus documents only, without trash, model weights, database, private scratch, source recordings, settings or voiceprints. Stable opaque note IDs; no filesystem paths in URIs. Read uses a private locked snapshot, like explicit export; existing notes expose no write/delete/rename flags and reject all write modes.
- System picker enumerates through its signature permission. Other apps can access only document/tree URIs the user grants through SAF. A single-document grant does not expose siblings. Root/tree grants, when explicitly chosen, expose the notes folder as the user requested.
- Create accepts new audio/Cassini documents, not replacement of existing IDs. New handles are write-only bounded pipes. Enforce actual byte and free-space limits while staging, check remote close errors, then invoke normal import with original display name and digest deduplication. Existing note writes remain unavailable. ZIP batch import is in Task B's picker.
- Incoming IDs need durable aliases to the resulting Created/Existing note ID: the URI returned by createDocument must not become invalid merely because import finished or the process restarted. Store only handle metadata and result note ID in a private provider index; no duplicate audio. Interrupted/failed staging is swept on restart. A completed alias resolves only while its target remains live.
- Cancellation/pipe failure must leave no partial note or private upload; surface ordinary import failures in existing notices. Previously completed imports are preserved. Bound pending handles to avoid resource exhaustion.
- Tests: protected manifest/no command component, root/child projection, byte-identical read snapshots, attempts to overwrite/delete/escape IDs, new-file import/dedup, read-after-create and provider recreation, partial/aborted/oversized upload cleanup, cancellation; API 35 Files integration and denied ungranted access from a separate test-app UID.

Android's SAF manifest/permission/grant contract: https://developer.android.com/guide/topics/providers/create-document-provider . This adds the same user-authorized file export/import paths available in the app; it adds no general filesystem or database access. It makes explicit system-picker grants usable by Files and other user-selected apps.

## Resource bounds and failure behavior

The provider admits eight concurrent read snapshots and eight incoming pipes, with a shared 4 GiB private byte budget, a 128 MiB free-space margin, and at most 4,096 durable import handles. Limits are checked against bytes actually received. Incoming handles cannot be overwritten or claimed twice; successful deduplication resolves to the original note. Reliable-pipe errors reject even a complete valid payload, preventing accidental import after the sender aborts. Cancellation enters the same cleanup path even if the receiver was still queued. Atomic metadata recovery preserves completed aliases and removes interrupted uploads at the next provider initialization.

Read snapshots are independent of later edits, and closing the descriptor releases its staging reservation. A read URI stops resolving when its target is trashed. The provider does not expose thumbnails, recents, search, delete, rename, move, replacement writes or filesystem paths. No release command surface or new dangerous permission is added. The permission probe activity exists only in the instrumentation APK.
