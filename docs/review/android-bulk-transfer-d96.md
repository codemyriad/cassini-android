# Android bulk transfer (D96)

The shared contract and complete strings catalog are pinned to cassini-ux `4d577376f6360ed352fa75307f4dc47890ed9858`. `third_party/cassini-ux/UPSTREAM.json` records the catalog SHA-256. `scripts/generate-d96-strings.py` generates resources in all six app locales; CI checks them against the pinned source. The old app-specific bulk copy is removed. Summaries show only nonzero shared plural parts, cancellation has the shared import-cancelled message, and import/export use their distinct progress strings. Android's picker uses MIME types; its supplied title uses the shared filter label where the picker displays one.

The picker uses OpenMultipleDocuments. Direct audio and archive entries use the same ImportRepository copy/hash, content classification, digest deduplication and catalogue insertion path. Original entry names are passed separately from private random staging paths. A batch runs on IO and outlives its screen. Cancellation closes blocked provider streams; it is also acknowledged when IO was still queued. Already imported files remain, with their available summary.

## Archive contract

Every archive is privately spooled, then its complete central directory is inspected before any import. Classic ZIP and ZIP64 directory sizes, offsets, disk fields and entry ranges are validated with bounded metadata reads. Local-header names and encryption flags are checked too. More than 2,000 entries, more than 20 GB total uncompressed bytes (decimal), or an entry ratio above 100:1 refuses the archive. These limits include ignored entries. Every absolute path or parent component refuses the whole archive. Relative paths are reduced to their base name. Encrypted entries, including AES markers, refuse the whole archive without a password prompt.

Finder AppleDouble sidecars under __MACOSX are unsupported files. All classic ZIP64 EOCD sentinel fields are accepted only with a validated single-disk ZIP64 record. Local and central headers must agree on names, compression and the UTF-8 flag; streaming data-descriptor flags may differ. Encryption is rejected in either header.

After directory validation, entry data is read from its bounded range. An entry that exceeds a forged declared size stops staging excess bytes but continues draining through the actual archive-wide byte and ratio checks. Actual output bytes, CRC, lengths and inflater consumption are checked. Forged directory sizes cannot bypass the expanded-byte or ratio limits. Validation/staging precedes normal imports, so an actual whole-archive bound failure also leaves no notes imported from that archive. A damaged individual supported entry is counted as failed and other notes can proceed. An entry over Android's existing single-file limit is not imported and counts as failed. Unsupported files and nested archives are counted without recursive import; an archive with no supported entry uses the shared empty-archive error. Broken directories use the shared damaged-archive error.

Only stored and deflated entry data is decoded. A supported audio entry with another ZIP compression method counts as failed. The parser reads metadata; Java's inflater decodes deflated data. Buffers stay fixed-size. Source reads and filesystem work stay off the UI thread. Spooling and staging preserve the existing free-space margin rather than allocating the archive in memory.

## Export and cleanup

The explicit selector initially includes all eligible notes; unchecking notes exports only the remaining selection. A recording in progress is excluded. Cassini documents retain their complete .opus contents; per Silvio's clarification, plain audio retains its original format. Names use ExportNames.file, with numeric suffixes for case-insensitive collisions. No manifest, model, catalogue or voiceprint file is added.

Title synchronization uses the same guard as single-note export: only a title-dirty, OK Cassini document is retitled. Exporting an unedited imported document preserves both its stored bytes and its import digest, including documents with an absent or mismatching TITLE tag. A non-OK document is not retitled.

The destination opens first. One note is copied under its writer lock to a private immutable snapshot, within the remaining archive and single-file budget; CRC32 is computed during that same copy. The snapshot becomes one STORED ZIP entry streamed directly to the buffered destination, then is deleted before the next note is copied. No complete ZIP or batch of snapshots is staged. Temporary storage is bounded by one note, in addition to the destination itself. Export uses the same 2,000-entry and 20 GB limits, so its output remains importable. Failed or cancelled destination writes attempt to delete the incomplete document; a provider may refuse deletion.

The live selector excludes active captures, unplayable notes and missing files. Both All actions are disabled with no exportable choices, including the initial loading state. A real picked batch goes through LibraryViewModel/LibraryPorts and clears a prior single-file import error. Cancelling the picker does neither.

The Library route owns transfer-state and nullable catalogue collection, the export result launcher and all chooser/result dialogs. The lazy header contains only the inline trigger, so scrolling it out cannot dispose progress, cancellation, summaries or a pending destination callback. A null catalogue means loading: restoration retains the saved partial selection until a real catalogue emission can initialize or prune it. A loaded empty list still removes genuinely unavailable choices.

Private staging is removed before completion is published, including cancellation and malformed input. The next process clears interrupted staging before a new transfer. Transfers do not promise resumption after process death. Completed normal imports remain valid if a later file fails or the person cancels.

Task A ownership is preserved: DocumentRepository.kt, CassiniDocument.kt, the format pin and meeting-time semantics are unchanged. AppGraph adds only the transfer binding. Files-provider access remains a separate PR.

ZIP record reference: [PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).

## Review regression coverage

- Missing/mismatching TITLE imports remain byte-identical after ZIP export and re-import as duplicates; dirty OK titles update, quarantined titles do not.
- Mid-read provider cancellation covers both EOF and IOException; mid-write cancellation dismisses cleanly, with no note or staging left from cancellation. Queued cancellation and ordinary IO failures remain distinct.
- A good entry beside a forged-size bomb refuses the whole archive under default limits and with a cap larger than one read buffer.
- Finder sidecars, every ZIP64 EOCD sentinel and local-only descriptor flags have compatibility cases.
- A controlled destination proves it opens before staging and sees no more than one snapshot. All entries remain STORED and byte-identical.
- Unplayable/missing/live notes are excluded, empty-Library export is disabled, and the ViewModel preserves multi-URI picks and ignores picker cancellation.

Final local/device/mutation and exact-head CI results are recorded in the PR; this design note does not treat an older green head as validation of later fixes.
