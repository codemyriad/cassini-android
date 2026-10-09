#!/usr/bin/env bash
# Shows how the code that 0.2 keeps verbatim differs from 0.1.0.
#
# For every group below, the old file(s) at the phase-0 base and the new file(s) in the working tree are joined
# and compared, after removing `package` and `import` lines and the `internal` modifier (the modules need the
# declarations public). What remains is printed as a unified diff, so a reviewer sees only the edits that
# docs/architecture.md (sections 2.1 and 2.2) lists for each file. A group of kind "restructured" was allowed to
# change shape; it is shown for reference.
#
# Changes in the amount of white space are ignored, so a block that only moved one nesting level (for example
# from a companion object to a top-level object) shows as unchanged; re-wrapped lines still show.
#
# Usage: scripts/check-verbatim.sh [--summary] [--exact] [--base <commit>] [group...]
#   --summary       print only the table of changed line counts
#   --exact         also show changes in the amount of white space
#   --base          the 0.1.0 code to compare with (default 0ebea43, the phase-0 base)
#   group...        only these groups (names from the table)
# Exits 0 when every file was found, 2 when a file is missing. Differences alone never fail the script.
set -euo pipefail
cd "$(dirname "$0")/.."

base=0ebea43
summary=false
diff_flags=(-u -B -b)
only=()
while (($#)); do
    case "$1" in
        --summary) summary=true ;;
        --exact) diff_flags=(-u -B) ;;
        --base) base="$2"; shift ;;
        -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
        *) only+=("$1") ;;
    esac
    shift
done

old=app/src/main/java/org/cassini/android
core=core/src/main/kotlin/org/cassini/core
engine=engine/src/main/kotlin/org/cassini/engine
app=app/src/main/java/org/cassini/android

# name | kind | old files (at the base) | new files (working tree) | the edits the plan allows
# A new file written as `path#regex` contributes only its lines from the first line matching regex to the end.
groups=(
    "OggOpus|verbatim|$old/OggOpus.kt|$core/format/OggOpus.kt|package only"
    "CassiniPayload|verbatim|$old/CassiniPayload.kt|$core/format/CassiniPayload.kt|package only"
    "CassiniDocument|verbatim|$old/CassiniDocument.kt|$core/format/CassiniDocument.kt|DocumentState wrapper and readChecked (UNREADABLE); read/validate/seal/relabel unchanged"
    "Transcript|verbatim|$old/Transcript.kt|$core/format/Transcript.kt $core/asr/TokenWords.kt|fromTokens moved to asr/TokenWords.kt"
    "RecoveryScanner|verbatim|$old/RecoveryScanner.kt|$core/format/OggRepair.kt $app/RecoveryScanner.kt|repair/endsCleanly in core OggRepair; recover() stays in the app"
    "SpeakerAttribution|verbatim|$old/SpeakerAttribution.kt|$core/format/SpeakerAttribution.kt|the model pins come from ModelCatalog"
    "SpeakerMerges|verbatim|$old/SpeakerMerges.kt|$core/format/SpeakerMerges.kt|package only"
    "Resampler|verbatim|$old/Resampler.kt|$core/audio/Resampler.kt|package only"
    "PcmSource|verbatim|$old/PcmSource.kt|$core/audio/PcmSource.kt|PcmAudio moved here from AudioDecoder.kt"
    "Limits|verbatim|$old/Limits.kt|$core/audio/AudioLimits.kt $app/ui/Format.kt $core/audio/PcmBuilder.kt|split into AudioLimits, PcmBuilder and the app's clock()"
    "AudioDecoder|verbatim|$old/AudioDecoder.kt|$engine/decode/AudioDecoder.kt $core/audio/WavDecoder.kt|ContentResolver + cacheDir instead of Context; framePosition/decodeWav in core WavDecoder; PcmAudio in PcmSource.kt"
    "SpeechWindows|verbatim|$old/SpeechWindows.kt|$core/asr/SpeechWindows.kt|package only"
    "SeamMerge|verbatim|$old/SeamMerge.kt|$core/asr/SeamMerge.kt|package only"
    "WordGate|verbatim|$old/WordGate.kt|$core/asr/WordGate.kt|package only"
    "IncrementalTranscript|verbatim|$old/IncrementalTranscript.kt|$core/asr/IncrementalTranscript.kt|typed IncrementalState instead of JSONObject"
    "TranscriptionProgress|verbatim|$old/TranscriptionProgress.kt|$core/asr/TranscriptionProgress.kt|package only"
    "ProcessingSpeed|verbatim|$old/ProcessingSpeed.kt|$core/asr/ProcessingSpeed.kt|remainingMs deleted (dead code)"
    "BatteryProjection|verbatim|$old/BatteryProjection.kt|$core/asr/BatteryProjection.kt $app/BatteryReader.kt|read() moved to the app's BatteryReader"
    "SpeechDetector|verbatim|$old/SpeechDetector.kt|$core/asr/SpeechFeed.kt $engine/asr/SileroDetector.kt|companion -> core SpeechFeed with a core Segment; native half -> SileroDetector"
    "Parakeet|verbatim|$old/Parakeet.kt|$engine/asr/Parakeet.kt $engine/asr/IncrementalRecognizer.kt $engine/asr/ParakeetDecoder.kt $core/asr/DecodeSession.kt|Decoder -> ParakeetDecoder (WordDecoder, config(), no lease); Session -> DecodeSession; Incremental -> IncrementalRecognizer; transcribe takes ParakeetFiles"
    "CheckpointData|verbatim|$old/CheckpointData.kt|$core/checkpoint/CheckpointData.kt|package only"
    "Diarization|verbatim|$old/Diarization.kt|$core/speakers/SpeakerAssignment.kt $core/speakers/TurnHistory.kt $engine/speakers/SortformerStream.kt|assign -> SpeakerAssignment; history trimming -> TurnHistory; native lifetime -> SortformerStream over NativeBudget; config() helper; the progress callback compiled as a class (P0, 67d36b4)"
    "VoiceMatcher|verbatim|$old/VoiceMatcher.kt|$core/speakers/VoiceMatcher.kt|package only"
    "VoiceClipCache|verbatim|$old/VoiceClipCache.kt|$core/speakers/VoiceClipCache.kt|8 speakers / 45 s from SpeakerBudget"
    "Voiceprints|verbatim|$old/Voiceprints.kt|$core/speakers/VoiceprintSelection.kt $engine/speakers/VoiceprintExtractor.kt|compute() -> VoiceprintExtractor without leaseHeld; 45 s from SpeakerBudget, the model pin from ModelCatalog"
    "VoiceStore|verbatim|$old/VoiceStore.kt|$core/speakers/VoiceCodec.kt $app/VoiceStore.kt|Voice JSON and enrol/unenrol maths -> core VoiceCodec; file I/O stays in the app"
    "NoteVoices|verbatim|$old/NoteVoices.kt|$core/speakers/NoteVoiceCodec.kt $app/NoteVoices.kt|Match/SpeakerPrint/JSON -> core NoteVoiceCodec (a version mismatch throws); file I/O stays in the app"
    "LibraryNote|verbatim|$old/LibraryNote.kt|$core/library/NoteRules.kt $app/LibraryStore.kt#^internal.fun.libraryNote|rules + Session in core NoteRules; the JSON mapping stays in the app's LibraryStore"
    "UserFacingException|verbatim|$old/UserFacingException.kt|$core/Failure.kt $app/FailureMessages.kt|Failure without R.string; FailureMessages maps codes to strings"
    "StreamingOpusEncoder|verbatim|$old/StreamingOpusEncoder.kt|engine/src/main/kotlin/org/cassini/android/NativeOpus.kt $engine/opus/StreamingOpusEncoder.kt|NativeOpus split out, JNI package kept"
    "MicrophoneRecording|verbatim|$old/MicrophoneRecording.kt|$engine/capture/MicrophoneCapture.kt $engine/capture/OpusCapture.kt $engine/capture/AacCapture.kt $engine/capture/CaptureFactory.kt|interface with suspend stop(); Opus/Aac loops unchanged; format probe cached off the main thread"
    "opus-jni.cpp|verbatim|app/src/main/cpp/opus-jni.cpp|engine/src/main/cpp/opus-jni.cpp|ptr == 0 guards, FindClass null check, opus_encoder_ctl return checks"
    "CMakeLists.txt|verbatim|app/src/main/cpp/CMakeLists.txt|engine/src/main/cpp/CMakeLists.txt|built by AGP externalNativeBuild instead of a script"
    "Models|restructured|$old/ModelStore.kt $old/DiarizationModels.kt $old/VoiceprintModel.kt $old/ModelBundle.kt|$core/models/ArtifactFetcher.kt $core/models/ModelCatalog.kt $app/InstalledModels.kt|one descriptor table; fetch/fetchZstd/decompress/digest in ArtifactFetcher with Range resume"
    "ProcessingCheckpoint|restructured|$old/ProcessingCheckpoint.kt|$app/ProcessingCheckpoint.kt|typed CheckpointState codec and validation rules in core"
)

# Prints a file from the base (old) or the working tree (new), without package/import lines and `internal`.
normalise() { sed -E -e '/^[[:space:]]*(package|import)[[:space:]]/d' -e 's/\binternal //g'; }
old_text() { local f; for f in "$@"; do git show "$base:$f" || return 1; echo; done; }
new_text() {
    local spec path pattern
    for spec in "$@"; do
        path="${spec%%#*}"
        [[ -f "$path" ]] || { echo "missing: $path" >&2; return 1; }
        if [[ "$spec" == *#* ]]; then
            pattern="${spec#*#}"
            awk -v re="$pattern" 'found || $0 ~ re { found = 1; print }' "$path"
        else
            cat "$path"
        fi
        echo
    done
}

git rev-parse --verify --quiet "$base^{commit}" >/dev/null || { echo "Unknown base commit $base" >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
missing=0
rows=()
for group in "${groups[@]}"; do
    IFS='|' read -r name kind olds news edits <<<"$group"
    if ((${#only[@]})) && [[ " ${only[*]} " != *" $name "* ]]; then continue; fi
    read -r -a old_files <<<"$olds"
    read -r -a new_files <<<"$news"
    if ! old_text "${old_files[@]}" 2>/dev/null | normalise >"$work/old"; then
        echo "missing at $base: $olds" >&2; missing=1; continue
    fi
    if ! new_text "${new_files[@]}" | normalise >"$work/new"; then missing=1; continue; fi
    diff "${diff_flags[@]}" --label "0.1.0: $olds" --label "now: $news" "$work/old" "$work/new" >"$work/diff" || true
    removed=$(grep -c '^-[^-]' "$work/diff" || true)
    added=$(grep -c '^+[^+]' "$work/diff" || true)
    rows+=("$(printf '%-22s %-12s %6s %6s  %s' "$name" "$kind" "-$removed" "+$added" "$edits")")
    if ! $summary; then
        printf '\n==== %s (%s): %s\n' "$name" "$kind" "$edits"
        if [[ -s "$work/diff" ]]; then cat "$work/diff"; else echo "(identical apart from package, imports and internal)"; fi
    fi
done

printf '\n%-22s %-12s %6s %6s  %s\n' group kind lines lines "allowed edits"
printf '%s\n' "${rows[@]}"
exit $((missing ? 2 : 0))
