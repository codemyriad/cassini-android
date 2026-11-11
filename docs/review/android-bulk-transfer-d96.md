# Android bulk transfer (D96)

The shared contract and complete strings catalog are pinned to cassini-ux `4d577376f6360ed352fa75307f4dc47890ed9858`. `third_party/cassini-ux/UPSTREAM.json` records the catalog SHA-256. `scripts/generate-d96-strings.py` generates resources in all six app locales; CI checks them against the pinned source. The old app-specific bulk copy is removed. Summaries show only nonzero shared plural parts, cancellation has the shared import-cancelled message, and import/export use their distinct progress strings. Android's picker uses MIME types; its supplied title uses the shared filter label where the picker displays one.

The picker uses OpenMultipleDocuments. Direct audio and archive entries use the same ImportRepository copy/hash, content classification, digest deduplication and catalogue insertion path. Original entry names are passed separately from private random staging paths. A batch runs on IO and outlives its screen. Cancellation closes blocked provider streams; it is also acknowledged when IO was still queued. Already imported files remain, with their available summary.

## Archive contract

Every archive is privately spooled, then its complete central directory is inspected before any import. Classic ZIP and ZIP64 directory sizes, offsets, disk fields and entry ranges are validated with bounded metadata reads. Local-header names and encryption flags are checked too. More than 2,000 entries, more than 20 GB total uncompressed bytes (decimal), or an entry ratio above 100:1 refuses the archive. These limits include ignored entries. Every absolute path or parent component refuses the whole archive. Relative paths are reduced to their base name. Encrypted entries, including AES markers, refuse the whole archive without a password prompt.

After directory validation, entry data is read from its bounded range. Actual output bytes, CRC, lengths and inflater consumption are checked. Forged directory sizes cannot bypass the expanded-byte or ratio limits. Validation/staging precedes normal imports, so an actual whole-archive bound failure also leaves no notes imported from that archive. A damaged individual supported entry is counted as failed and other notes can proceed. An entry over Android's existing single-file limit is not imported and counts as failed. Unsupported files and nested archives are counted without recursive import; an archive with no supported entry uses the shared empty-archive error. Broken directories use the shared damaged-archive error.

Only stored and deflated entry data is decoded. A supported audio entry with another ZIP compression method counts as failed. The parser reads metadata; Java's inflater decodes deflated data. Buffers stay fixed-size. Source reads and filesystem work stay off the UI thread. Spooling and staging preserve the existing free-space margin rather than allocating the archive in memory.

## Export and cleanup

The explicit selector initially includes all eligible notes; unchecking notes exports only the remaining selection. A recording in progress is excluded. Cassini documents retain their complete .opus contents; per Silvio's clarification, plain audio retains its original format. Names use ExportNames.file, with numeric suffixes for case-insensitive collisions. No manifest, model, catalogue or voiceprint file is added.

Title synchronization uses the existing repository behavior. Each note is copied under its writer lock to a private immutable snapshot, within the remaining archive and single-file budget. CRC and length come from the snapshot. ZIP entries are STORED, without compression. Export uses the same 2,000-entry and 20 GB limits, so its output remains importable. The complete ZIP is staged before delivery. Failed destination writes attempt to delete the incomplete document; a provider may refuse deletion.

Private staging is removed before completion is published, including cancellation and malformed input. The next process clears interrupted staging before a new transfer. Transfers do not promise resumption after process death. Completed normal imports remain valid if a later file fails or the person cancels.

Task A ownership is preserved: DocumentRepository.kt, CassiniDocument.kt, the format pin and meeting-time semantics are unchanged. AppGraph adds only the transfer binding. Files-provider access remains a separate PR.

ZIP record reference: [PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).
