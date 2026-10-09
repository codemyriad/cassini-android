# native/m2: repo-local Maven repository

The app depends on the patched sherpa-onnx runtime as an ordinary Maven coordinate:

    org.cassini.native:sherpa-onnx-nemotron:1.13.7-1f23907a

`settings.gradle.kts` reads that coordinate, and only the group `org.cassini.native`, from this
directory. Everything here except this README is ignored by git.

`scripts/setup.sh` fills the directory. It uses the first of these that works:

1. the AAR already installed here, if its SHA-256 matches the pin;
2. `app/libs/sherpa-onnx-1.13.7-nemotron.aar` from an older checkout, if its SHA-256 matches the pin;
3. the AAR attached to the GitHub release `native-sherpa-onnx-1.13.7-nemotron.2` (`scripts/fetch-native-aar.sh`);
4. a build from the pinned source plus `scripts/streaming-diarization.patch` (`scripts/build-native-aar.sh`, needs the NDK).

`scripts/setup.sh --from-source` skips straight to step 4. The pin (URL, SHA-256 and the hash of the
patch it was built from) is the `sherpa-onnx-aar` entry in `scripts/native-dependencies.json`;
`scripts/verify-native-runtime.sh` checks the installed file against it.

Layout written by the scripts:

    org/cassini/native/sherpa-onnx-nemotron/1.13.7-1f23907a/
        sherpa-onnx-nemotron-1.13.7-1f23907a.aar
        sherpa-onnx-nemotron-1.13.7-1f23907a.pom
