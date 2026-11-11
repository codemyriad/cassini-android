# Recording time (D95)

Format conformance is pinned to cassini-format 32f37a6200c88bdd4d23c1f44497f1761f3401da,
including vectors 027–029. Recording time is `recordedAtUtc`, then an offset-bearing
DATE that differs from the packaging instant, then the literal `recordedAtLocal` label.
`createdAtUtc` is used only for ordering when no recording instant is known.

Android captures UTC at playback position zero. It does not know the recording site's
zone and does not invent a `recordedAtLocal`. Sealing writes the UTC mirror and DATE;
appending a version preserves the original meeting metadata. Legacy imports with no
known start show “Date unknown”. Local labels are displayed without zone conversion.

Schema 2 preserves notes and jobs, adds a nullable derived recording-time cache and an independent
own-capture UTC clock, and
invalidates old document signatures. Startup re-derives this finite set off Main, before
the Library is ready; opening individual notes is not required.
It does not rewrite imported documents or infer a recording start from the old document cache.
Own v1 document clocks are held provisionally until a verified file distinguishes an original
capture clock from the exact packaging timestamp of a rebuilt catalogue. Real v1 producer
and rebuild fixtures cover both paths. Audio identity binds the retained capture clock;
adoption of changed audio clears it, including replacement with the same meeting id. Damaged/unknown-major manifests cannot promote DATE
tags to a meeting time, matching the pinned Player `when` gate. Re-export of an own
legacy document fills its independently known UTC in a staged copy, without changing a
running job's canonical file or checkpoint signature. The production worker and the
repository sealer both receive the capture clock. Replacing stale audio inherits neither
the old local label nor its old UTC clock. Save a copy preserves non-OK files byte for byte.

The cache retains the recording-time source, so even an offset-bearing local label stays
a label. Library and search order known times by their displayed calendar fields, without
assigning a zone to floating local labels; packaging time orders unknown starts only.

The Date unknown translations are copied verbatim from cassini-ux
3216b2db9abd215dc7e283a02dffb9e40f336fe5 (strings/strings.json, library.group.unknownDate).
Source SHA-256: 109a72f01e223dcfa92e69186b04d233fa479f49150b4704c9853fd0b87077bb
