# shellcheck shell=bash
# Shared helpers for the native runtime scripts. Source it from the repository root:
#   . scripts/native-common.sh

# pin <entry name> <field>: print one field of scripts/native-dependencies.json.
pin() {
    python3 -I -c 'import json, sys
entry = next(e for e in json.load(open(sys.argv[1])) if e["name"] == sys.argv[2])
print(entry[sys.argv[3]])' scripts/native-dependencies.json "$1" "$2"
}

# sha256_of <file>: print the file's SHA-256.
sha256_of() {
    sha256sum "$1" | cut -d' ' -f1
}

# The repo-local Maven repository that settings.gradle.kts reads the AAR from.
aar_version=$(pin sherpa-onnx-aar version)
aar_directory=native/m2/org/cassini/native/sherpa-onnx-nemotron/$aar_version
aar_installed=$aar_directory/sherpa-onnx-nemotron-$aar_version.aar

# install_aar <file>: copy an AAR into native/m2 with a minimal POM. It does not check the pin;
# scripts/verify-native-runtime.sh does.
install_aar() {
    mkdir -p "$aar_directory"
    cp "$1" "$aar_installed.part"
    mv "$aar_installed.part" "$aar_installed"
    cat > "$aar_directory/sherpa-onnx-nemotron-$aar_version.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.cassini.native</groupId>
  <artifactId>sherpa-onnx-nemotron</artifactId>
  <version>$aar_version</version>
  <packaging>aar</packaging>
</project>
POM
    printf 'Installed %s (sha256 %s)\n' "$aar_installed" "$(sha256_of "$aar_installed")"
}

# ndk_directory: print the NDK to build with. ANDROID_NDK wins; otherwise the pinned NDK inside
# ANDROID_HOME, ANDROID_SDK_ROOT or local.properties' sdk.dir.
ndk_directory() {
    if [ -n "${ANDROID_NDK:-}" ]; then
        [ -d "$ANDROID_NDK" ] || return 1
        printf '%s\n' "$ANDROID_NDK"
        return
    fi
    local version sdk
    version=$(sed -n 's/^ndk *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)"; do
        if [ -n "$sdk" ] && [ -d "$sdk/ndk/$version" ]; then
            printf '%s\n' "$sdk/ndk/$version"
            return
        fi
    done
    return 1
}
