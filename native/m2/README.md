# native/m2: repository-local Maven repository

The app uses the patched sherpa-onnx runtime as an ordinary Maven coordinate:

    org.cassini.native:sherpa-onnx-nemotron:1.13.7-dbbeef9c

`settings.gradle.kts` reads this coordinate from this directory. It reads only the group `org.cassini.native`
from here. Git ignores all files in this directory except this README.

`scripts/setup.sh` fills the directory. It uses the first of these sources that operates:

1. The AAR that is already in this directory, if its SHA-256 agrees with the pin.
2. The file `app/libs/sherpa-onnx-1.13.7-nemotron.aar` of an older checkout, if its SHA-256 agrees with the pin.
3. The AAR of the GitHub release `native-sherpa-onnx-1.13.7-nemotron.3` (`scripts/fetch-native-aar.sh`).
4. A build from the pinned source and `scripts/streaming-diarization.patch` (`scripts/build-native-aar.sh`). This
   build needs the NDK.

`scripts/setup.sh --from-source` goes directly to source 4. `scripts/setup.sh --prebuilt-only` stops with an error
and does not use source 4. CI uses `--prebuilt-only`.

The pin is the `sherpa-onnx-aar` entry in `scripts/native-dependencies.json`. It holds the URL, the SHA-256 of the
AAR and the SHA-256 of the patch that the AAR contains. `scripts/verify-native-runtime.sh` compares the installed
file with the pin.

The scripts write this layout:

    org/cassini/native/sherpa-onnx-nemotron/1.13.7-dbbeef9c/
        sherpa-onnx-nemotron-1.13.7-dbbeef9c.aar
        sherpa-onnx-nemotron-1.13.7-dbbeef9c.pom
