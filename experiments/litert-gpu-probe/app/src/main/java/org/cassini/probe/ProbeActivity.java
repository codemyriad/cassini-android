package org.cassini.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import com.google.ai.edge.litert.*;
import java.nio.file.*;
import org.json.*;

public class ProbeActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView view = new TextView(this);
    view.setText("Running Parakeet benchmark…");
    setContentView(view);
    String mode = getIntent().getStringExtra("accelerator");
    if (mode == null) mode = "gpu";
    final String selected = mode;
    new Thread(
            () -> {
              JSONObject report = new JSONObject();
              long start = System.nanoTime();
              try {
                report.put("device", android.os.Build.MODEL);
                report.put("soc", android.os.Build.SOC_MODEL);
                report.put("runtime", "LiteRT 2.2.0");
                report.put("accelerator", selected);
                CompiledModel.Options options =
                    (selected.equals("cpu") || selected.equals("hybrid"))
                        ? new CompiledModel.Options(Accelerator.CPU)
                        : selected.equals("gpu-cpu")
                            ? new CompiledModel.Options(Accelerator.GPU, Accelerator.CPU)
                            : new CompiledModel.Options(Accelerator.GPU);
                try (CompiledModel model =
                    CompiledModel.Companion.create(getFilesDir() + "/model.tflite", options)) {
                  report.put("compiled", true);
                  report.put("success", true);
                  report.put("compileMs", (System.nanoTime() - start) / 1e6);
                  if (selected.equals("hybrid")) {
                    try (CompiledModel encoder =
                        CompiledModel.Companion.create(
                            getFilesDir() + "/model.tflite",
                            new CompiledModel.Options(Accelerator.GPU))) {
                      report.put("compileMs", (System.nanoTime() - start) / 1e6);
                      report.put("inference", new TdtProbe(model, encoder, getFilesDir()).run());
                    }
                  } else report.put("inference", new TdtProbe(model, model, getFilesDir()).run());
                }
              } catch (Throwable e) {
                try {
                  report.put("success", false);
                  report.put("error", Log.getStackTraceString(e));
                  report.put("elapsedMs", (System.nanoTime() - start) / 1e6);
                } catch (Exception ignored) {
                }
              }
              try {
                Files.write(
                    getFilesDir().toPath().resolve("report-" + selected + ".json"),
                    report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
              } catch (Exception e) {
                Log.e("CassiniGpuProbe", "write", e);
              }
              Log.i("CassiniGpuProbe", report.toString());
              runOnUiThread(() -> view.setText(report.toString()));
            })
        .start();
  }
}
