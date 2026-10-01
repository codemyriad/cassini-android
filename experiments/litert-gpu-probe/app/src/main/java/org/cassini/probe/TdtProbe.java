/* Adapted from Google LLC's 2026 LiteRT ASR TdtDecoder.kt, Apache License 2.0.
 * https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition
 * Licensed under https://www.apache.org/licenses/LICENSE-2.0; provided AS IS without warranties.
 */
package org.cassini.probe;

import com.google.ai.edge.litert.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

class TdtProbe {
  final CompiledModel model, encoder;
  final File directory;

  TdtProbe(CompiledModel model, CompiledModel encoder, File directory) {
    this.model = model;
    this.encoder = encoder;
    this.directory = directory;
  }

  int elements(TensorType t) {
    int n = 1;
    for (int x : t.getLayout().getDimensions()) n *= x;
    return n;
  }

  int argmax(float[] v, int a, int b) {
    int j = a;
    for (int k = a + 1; k < b; k++) if (v[k] > v[j]) j = k;
    return j - a;
  }

  JSONArray run() throws Exception {
    JSONObject vocab =
        new JSONObject(
                new String(Files.readAllBytes(new File(directory, "tokenizer.json").toPath())))
            .getJSONObject("model")
            .getJSONObject("vocab");
    Map<Integer, String> tokens = new HashMap<>();
    for (Iterator<String> it = vocab.keys(); it.hasNext(); ) {
      String t = it.next();
      tokens.put(vocab.getInt(t), t);
    }
    List<TensorBuffer> encIn = encoder.createInputBuffers("encode"),
        encOut = encoder.createOutputBuffers("encode");
    List<TensorBuffer> decIn = model.createInputBuffers("decode"),
        decOut = model.createOutputBuffers("decode");
    List<TensorBuffer> oneIn = model.createInputBuffers("decode_1"),
        oneOut = model.createOutputBuffers("decode_1");
    int maxTime = elements(model.getInputTensorType("args_0", "decode")) / 1024;
    int tokenCount = elements(model.getInputTensorType("args_1", "decode"));
    int numLogits =
        elements(model.getOutputTensorType("output_0", "decode")) / tokenCount / maxTime;
    int numStates = elements(model.getInputTensorType("args_2", "decode"));
    android.util.Log.i(
        "CassiniGpuProbe",
        "Shape time=" + maxTime + " tokens=" + tokenCount + " logits=" + numLogits);
    List<List<TensorBuffer>> states =
        Arrays.asList(
            Arrays.asList(decIn.get(2), decIn.get(3)), Arrays.asList(decOut.get(1), decOut.get(2)));
    JSONArray reports = new JSONArray();
    for (int pass = 0; pass < 2; pass++) {
      JSONArray chunks = new JSONArray();
      long passStart = System.nanoTime();
      for (int chunk = 0; chunk < 6; chunk++) {
        byte[] raw = Files.readAllBytes(new File(directory, "features-" + chunk + ".bin").toPath());
        float[] mel = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(mel);
        long start = System.nanoTime();
        encIn.get(0).writeFloat(mel);
        encoder.run(encIn, encOut, "encode");
        if (encoder != model) decIn.get(0).writeFloat(encOut.get(0).readFloat());
        double encodeMs = (System.nanoTime() - start) / 1e6;
        int stateIndex = 0;
        for (TensorBuffer b : states.get(0)) b.writeFloat(new float[numStates]);
        String signature = "decode";
        int numTokens = tokenCount;
        TensorBuffer idsBuffer = decIn.get(1), logitsBuffer = decOut.get(0);
        int[] ids = new int[numTokens];
        ids[0] = 8192;
        int tokenIndex = 0, time = 0, steps = 0;
        StringBuilder text = new StringBuilder();
        JSONArray emitted = new JSONArray();
        long decodeStart = System.nanoTime();
        while (time < maxTime && steps++ < 1000) {
          idsBuffer.writeInt(ids);
          List<TensorBuffer> inputs =
              new ArrayList<>(encoder == model ? encOut : Collections.singletonList(decIn.get(0)));
          inputs.add(idsBuffer);
          inputs.addAll(states.get(stateIndex));
          List<TensorBuffer> outputs = new ArrayList<>();
          outputs.add(logitsBuffer);
          outputs.addAll(states.get(1 - stateIndex));
          model.run(inputs, outputs, signature);
          float[] logits = logitsBuffer.readFloat();
          int offset = (time * numTokens + tokenIndex) * numLogits;
          int token = argmax(logits, offset, offset + numLogits - 5);
          int duration = argmax(logits, offset + numLogits - 5, offset + numLogits);
          for (int k = offset; k < offset + numLogits; k++)
            if (!Float.isFinite(logits[k]))
              throw new IllegalStateException(
                  "Nonfinite logits at chunk=" + chunk + " step=" + steps);
          if (token != 8192) {
            text.append(tokens.getOrDefault(token, "<" + token + ">"));
            emitted.put(new JSONObject().put("id", token).put("frame", time));
            if (numTokens > 1) {
              tokenIndex++;
              if (tokenIndex >= numTokens) {
                signature = "decode_1";
                numTokens = 1;
                idsBuffer = oneIn.get(1);
                ids = new int[1];
                logitsBuffer = oneOut.get(0);
                tokenIndex = 0;
              }
            }
            ids[tokenIndex] = token;
          }
          time += (duration == 0 && token == 8192) ? 1 : duration;
          if (numTokens == 1 && token != 8192) stateIndex = 1 - stateIndex;
        }
        if (steps >= 1000) throw new IllegalStateException("Decoder step limit");
        JSONObject r =
            new JSONObject()
                .put("chunk", chunk)
                .put("encodeMs", encodeMs)
                .put("decodeMs", (System.nanoTime() - decodeStart) / 1e6)
                .put("text", text.toString().replace('▁', ' ').trim())
                .put("tokens", emitted);
        chunks.put(r);
        android.util.Log.i(
            "CassiniGpuProbe", "pass=" + pass + " chunk=" + chunk + " " + r.toString());
      }
      double ms = (System.nanoTime() - passStart) / 1e6;
      reports.put(
          new JSONObject()
              .put("pass", pass)
              .put("audioMs", 30000)
              .put("elapsedMs", ms)
              .put("realtime", 30000 / ms)
              .put("chunks", chunks));
    }
    for (List<TensorBuffer> list : Arrays.asList(encIn, encOut, decIn, decOut, oneIn, oneOut))
      for (TensorBuffer b : list) b.close();
    return reports;
  }
}
