// SPDX-License-Identifier: GPL-3.0-or-later
package io.codemyriad.cassini.whistlespike;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Standalone offline engine experiment; all JNI calls run serially on this instrumentation thread. */
public final class Spike extends Instrumentation {
    private static native void load(String path, byte[] model);
    private static native byte[] transcribe(byte[] pcm);
    private static final String[] FLAGS = {"NEEDLE_TELEMETRY", "DO_NOT_TRACK", "ORT_DISABLE_TELEMETRY"};
    private static final String[] VALUES = {"0", "1", "1"};

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private static byte[] read(InputStream input, int limit) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IllegalStateException("Input exceeds bound");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    private byte[] asset(String name, int limit) throws Exception { return read(getContext().getAssets().open(name), limit); }
    private byte[] modelAsset() throws Exception {
        // Exact pin size: avoid ByteArrayOutputStream's 32 MiB growth plus a second full copy
        // on API 26 guests with a 48 MiB Java heap. Do not request a large heap to hide it.
        byte[] bytes = new byte[16919407];
        try (InputStream input = getContext().getAssets().open("whistle.cact")) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count <= 0) throw new IllegalStateException("Truncated model asset");
                offset += count;
            }
            require(input.read() == -1, "Oversized model asset");
        }
        return bytes;
    }
    private static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static void pins(byte[] data, String expected) throws Exception {
        require(hash(data).equals(expected), "Input SHA-256 mismatch");
    }
    private static void flags() throws Exception { for (int i = 0; i < 3; i++) Os.setenv(FLAGS[i], VALUES[i], true); }

    @Override public void onStart() {
        Bundle summary = new Bundle();
        JSONObject report = new JSONObject();
        try {
            Context context = getContext();
            JSONObject manifest = new JSONObject(new String(asset("manifest.json", 1048576), StandardCharsets.UTF_8));
            byte[] model = modelAsset();
            pins(model, manifest.getString("modelSha256"));
            File runtime = new File(context.getApplicationInfo().nativeLibraryDir, "libcassini_needle.so");
            pins(read(new FileInputStream(runtime), 4000000), manifest.getString("androidEngineSha256"));
            // This library has no dependency on Needle: its constructors cannot load the engine.
            System.loadLibrary("whistle_spike");
            for (int i = 0; i < 3; i++) {
                for (boolean missing : new boolean[]{true, false}) {
                    flags();
                    if (missing) Os.unsetenv(FLAGS[i]); else Os.setenv(FLAGS[i], "wrong", true);
                    try {
                        load("/nonexistent/privacy-guard-must-win.so", new byte[0]);
                        throw new AssertionError("Missing privacy refusal");
                    } catch (IllegalStateException expected) {
                        require("Privacy guard before dlopen".equals(expected.getMessage()), "Guard did not precede dlopen");
                    }
                }
            }
            flags();
            long before = SystemClock.elapsedRealtimeNanos();
            load(runtime.getAbsolutePath(), model);
            report.put("loadMs", (SystemClock.elapsedRealtimeNanos() - before) / 1000000.0);
            model = null;
            report.put("privacyRefusals", 6);
            report.put("sdk", Build.VERSION.SDK_INT);
            report.put("abi", Build.SUPPORTED_ABIS[0]);
            report.put("pageSize", Os.sysconf(android.system.OsConstants._SC_PAGESIZE));
            report.put("scope", "Packaged JNI on identical desktop raw PCM; no Android resampler/VAD or production-worker proof");
            JSONArray results = new JSONArray();
            JSONArray fixtures = manifest.getJSONArray("fixtures");
            boolean allWordsEqual = true;
            boolean allTimesWithin80ms = true;
            for (int i = 0; i < fixtures.length(); i++) {
                JSONObject fixture = fixtures.getJSONObject(i);
                byte[] pcm = asset(fixture.getString("file"), 1920000);
                pins(pcm, fixture.getString("inputSha256"));
                require(pcm.length == fixture.getInt("samples") * 4, "Wrong PCM length");
                before = SystemClock.elapsedRealtimeNanos();
                JSONObject actual = new JSONObject(new String(transcribe(pcm), StandardCharsets.UTF_8));
                double elapsed = (SystemClock.elapsedRealtimeNanos() - before) / 1000000.0;
                JSONArray expectedWords = fixture.getJSONObject("macResult").getJSONArray("words");
                JSONArray actualWords = actual.getJSONArray("words");
                boolean equal = expectedWords.length() == actualWords.length();
                double maxTimeDifference = 0;
                for (int j = 0; j < Math.min(expectedWords.length(), actualWords.length()); j++) {
                    JSONObject expectedWord = expectedWords.getJSONObject(j), actualWord = actualWords.getJSONObject(j);
                    equal &= expectedWord.getString("word").equals(actualWord.getString("word"));
                    for (String key : new String[]{"start", "end"}) {
                        maxTimeDifference = Math.max(maxTimeDifference, Math.abs(expectedWord.getDouble(key) - actualWord.getDouble(key)));
                    }
                }
                allWordsEqual &= equal;
                allTimesWithin80ms &= equal && maxTimeDifference <= 0.080000001;
                JSONObject result = new JSONObject().put("file", fixture.getString("file"))
                    .put("inputSha256", fixture.getString("inputSha256")).put("androidResult", actual)
                    .put("exactWordsEqual", equal).put("maxTimeDifferenceSeconds", maxTimeDifference)
                    .put("elapsedMs", elapsed).put("pssKiB", Debug.getPss());
                results.put(result);
                Log.i("WhistleSpike", result.toString());
            }
            report.put("results", results).put("allWordsEqual", allWordsEqual)
                .put("allTimesWithin80ms", allTimesWithin80ms);
            report.put("status", "completed"); // A completed run can still have parity differences.
            try (FileOutputStream out = context.openFileOutput("result.json", Context.MODE_PRIVATE)) {
                out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            summary.putString("report", report.toString());
            finish(allWordsEqual && allTimesWithin80ms ? -1 : 1, summary);
        } catch (Throwable error) {
            Log.e("WhistleSpike", "Spike failed", error);
            summary.putString("error", Log.getStackTraceString(error));
            finish(2, summary);
        }
    }
}
