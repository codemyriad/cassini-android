# Recording time (D95)

Format conformance is pinned to cassini-format 32f37a6200c88bdd4d23c1f44497f1761f3401da,
including vectors 027–029. Recording time is `recordedAtUtc`, then an offset-bearing
DATE that differs from the packaging instant, then the literal `recordedAtLocal` label.
`createdAtUtc` is used only for ordering when no recording instant is known.

Android captures UTC at playback position zero. It does not know the recording site's
zone and does not invent a `recordedAtLocal`. Sealing writes the UTC mirror and DATE;
appending a version preserves the original meeting metadata. Legacy imports with no
known start show “Date unknown”. Local labels are displayed without zone conversion.

Schema 2 preserves notes and jobs, adds a nullable derived recording-time cache, and
invalidates old document signatures so opening a document derives its metadata again.
It does not rewrite imported documents or infer a recording start from the old cache.

The Date unknown translations are copied verbatim from cassini-ux
3216b2db9abd215dc7e283a02dffb9e40f336fe5 (strings/strings.json, library.group.unknownDate).
Source SHA-256: 109a72f01e223dcfa92e69186b04d233fa479f49150b4704c9853fd0b87077bb
