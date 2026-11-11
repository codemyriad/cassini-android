# Real v1 recording-time migration fixture

Generated against unmodified production code at cassini-android d65fd02b (Room schema 1),
by `GenerateV1TimeFixtureTest.kt` in this directory. It is a generator, not a test source of
this checkout. Run it in a detached checkout of that commit under the heavy lock, setting
CASSINI_V1_TIME_FIXTURE_OUT to a scratch output directory, with
`:data:testDebugUnitTest --tests '*GenerateV1TimeFixtureTest'`.

The generator calls the v1 recording creation, finish, seal and adopt APIs. `own-v1.db`
retains that recording's original start. It then calls the v1 catalogue rebuilder against
the same document in an empty catalogue: `rebuilt-v1.db` has the packaging timestamp.
`expected.json` records both facts from that actual sequence. No timestamp is asserted
against elapsed test time. Tests migrate these database bytes with the production opener,
then export and import with the production repositories.

The audio packets in document.opus are the already committed CC-BY-4.0 FLEURS clip from
[the audio fixtures](../README.md); the English word body is synthetic. No new recording,
private data, model weights, device paths or credentials are included. Databases are fresh
scratch catalogues with one synthetic note and its cache. SQLite connections were closed
before copying, so no WAL/journal is required.
