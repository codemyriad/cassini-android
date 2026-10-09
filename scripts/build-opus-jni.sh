#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK:?Set ANDROID_NDK to an Android NDK directory}"
work=$PWD/.tools/opus
mkdir -p "$work"
sha=9480e329e989f70d69886ded470c7f8cfe6c0667cc4196d4837ac9e668fb7404
if ! printf '%s  %s\n' "$sha" "$work/source.tar.gz" | sha256sum --check --status 2>/dev/null; then
  curl --fail --location --retry 3 https://codeload.github.com/xiph/opus/tar.gz/refs/tags/v1.5.2 -o "$work/source.tar.gz.part"
  printf '%s  %s\n' "$sha" "$work/source.tar.gz.part" | sha256sum --check
  mv "$work/source.tar.gz.part" "$work/source.tar.gz"
fi
mkdir -p "$work/source"
tar xzf "$work/source.tar.gz" -C "$work/source" --strip-components=1
for abi in arm64-v8a x86_64; do
  cmake -S app/src/main/cpp -B "$work/$abi" \
    -DOPUS_SOURCE="$work/source" -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
  cmake --build "$work/$abi" --parallel 8
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$work/$abi/libcassini_opus.so" "app/src/main/jniLibs/$abi/"
done
sha256sum scripts/build-opus-jni.sh app/src/main/cpp/CMakeLists.txt app/src/main/cpp/opus-jni.cpp \
  app/src/main/jniLibs/*/libcassini_opus.so > "$work/jni.sha256"
