// Cross-check Android output with the sibling web viewer's actual portable reader (Node 24+).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const filename = process.argv[2];
if (!filename) throw new Error('Usage: node scripts/check-portable-document.mjs DOCUMENT.opus');
const viewer = await import(pathToFileURL(path.resolve(root, '../gocassini/cassini-viewer/src/viewer/portable.ts')));
const { manifest, tags } = await viewer.extractPortableManifestFromArrayBuffer(fs.readFileSync(filename));
const id = viewer.getDefaultTranscriptId(manifest);
const entry = manifest.transcripts.find(value => value.id === id);
const body = await viewer.loadPortableTranscriptBody(tags, entry.payloadRef);
const projection = viewer.buildTranscriptWordsFromPortable({ ...manifest, transcript: body }, path.basename(filename));
assert.equal(projection.segments.length, body.items?.length ?? 0);
assert.equal(projection.media.durationMs, manifest.audio.durationMs);
console.log(JSON.stringify({ title: manifest.meeting.title, transcript: id, words: projection.segments.length,
  language: body.language, durationMs: projection.media.durationMs }, null, 2));
