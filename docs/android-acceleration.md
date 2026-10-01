# Android Parakeet acceleration investigation — 2026-10-01

**GPU execution is possible on the connected Pixel 8, including a quantized Parakeet TDT 0.6B v3 model.** An isolated LiteRT experiment compiled all three model subgraphs for OpenCL and transcribed the supplied Italian recording. This needs a different model conversion/runtime from the current sherpa AAR. Full GPU execution was slower than LiteRT CPU in this experiment; a GPU encoder with CPU decoding was modestly faster after loading.

The production app continues to use sherpa CPU and to record CPU in Cassini provenance. No experimental backend is selected automatically.

## Existing work and branches

| Candidate | What actually exists | Applicability |
| --- | --- | --- |
| [benniekiss/sherpa-onnx, `webgpu`](https://github.com/benniekiss/sherpa-onnx/tree/webgpu) | Native ONNX Runtime WebGPU provider registration and a build flag. [Discussion](https://github.com/k2-fsa/sherpa-onnx/issues/3665). | The branch's [CMake package](https://github.com/benniekiss/sherpa-onnx/blob/c8f2f33cd0c82bcded0819d34e0475c74b710866/cmake/onnxruntime-webgpu.cmake) accepts Linux x64, macOS arm64 and Windows x64/arm64, and explicitly rejects other systems. No Android arm64 binary, Android CI or demonstrated Parakeet v3 phone result. It is not an Android drop-in. |
| [Google LiteRT ASR Android sample](https://github.com/google-ai-edge/litert-samples/tree/385cfe0d09eeeb71f62daafd35ef6f353718720e/samples/litert/speech_recognition) | Official Kotlin Android runner, TDT decoder, conversion/verification tools, GPU support for multilingual TDT v3, and [converted models](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/tree/50dae0cb8c7b39dda477966eff7150cd7fe206ae). | **Tested on this Pixel**, with LiteRT 2.2.0 and the 5-second stateful INT8 conversion. The published Google Tensor NPU files target G5/G6, not this phone's G3. |
| [QVAC fabric speech / Parakeet ggml](https://github.com/tetherto/qvac-fabric-speech.cpp/tree/6b6f2bdefbecd0fee6bc51115006b336761478f5/engines/parakeet) | TDT v3 GGUF Q8/F16 support, GPU encoder and graph decoding; [Android package](https://github.com/tetherto/qvac/blob/03d764c6f998cc21335289dd3ac8df4095a853f0/packages/asr-ggml/README.md) supports Android arm64 12+ with Vulkan/OpenCL backends. | Its [backend policy](https://github.com/tetherto/qvac-fabric-speech.cpp/blob/6b6f2bdefbecd0fee6bc51115006b336761478f5/engines/parakeet/docs/backends.md) specifically selects Mali Vulkan for Parakeet encoder/TDT computation, with per-operation CPU fallback. Promising independent path; not built or measured here. Adreno OpenCL results are not Pixel results. |
| [mudler/parakeet.cpp](https://github.com/mudler/parakeet.cpp) | Multilingual TDT v3 GGUF support and a Vulkan build option. | A C++/ggml alternative, but its published prebuilt targets do not supply an Android Parakeet app/runtime. Requires an Android cross-build and validation; not measured here. |
| [surma/parakeeb](https://github.com/surma/parakeeb#onnx-runtime-execution-providers) | Android TDT v3 ONNX with XNNPACK/NNAPI experiments. | Reports that `nnapi-fp16,cpu` triggers Darwinn/TPU compilation on Pixel 8a but has high initialization latency and slower transcription than XNNPACK. This is another author's measurement, not ours, and TPU execution is not GPU execution. |

There is also a [soniqo Android SDK](https://github.com/soniqo/speech-android) and [LiteRT Parakeet implementation](https://github.com/soniqo/speech-core/blob/f0050757a7d7d24c60b17e60016989ecd07a491d/src/models/litert/litert_parakeet_stt.cpp). These confirm other integration work exists; their Android/NNAPI marketing does not establish a Pixel v3 GPU speedup. The smaller English EOU/CTC models are not replacements for our Italian TDT v3 target.

## Why changing the current provider does not enable GPU

The bundled sherpa-onnx 1.13.7 uses ONNX Runtime. Its [provider dispatch](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/csrc/provider.cc) has no generic Android Vulkan/OpenCL GPU provider. CUDA is for NVIDIA hardware and XNNPACK is CPU acceleration.

Its [NNAPI setup](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/csrc/session.cc) is conditionally compiled only when `__ANDROID_API__ >= 27`. Inspecting the actual bundled arm64 JNI/C API binaries finds the API-27 rejection message and no enabled-NNAPI branch messages, while their ONNX Runtime library contains the NNAPI provider. This is a wrapper build limitation; raising the app's minSdk alone does not change that compiled library.

The Pixel exposes `android.hardware.neuralnetworks.IDevice/google-edgetpu`. Rebuilding the wrapper could enable a separate NNAPI/TPU experiment, but does not guarantee graph coverage, useful performance, or GPU selection. The Parakeeb result makes this less attractive than the directly demonstrated LiteRT route.

INT8 is not inherently CPU-only. Our successful LiteRT run used quantized weights; accelerator arithmetic/layout and supported graph operations matter. That does **not** mean the existing ONNX files can be loaded by LiteRT, or that every GPU operation uses integer arithmetic. The probe leaves LiteRT GPU precision at its default rather than forcing FP32 compute.

## Pixel experiment and evidence

Device: Pixel 8 / Tensor G3 / Android 17. Runtime: LiteRT 2.2.0, separate `org.cassini.probe` APK. Model: `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite`, 614,261,072 bytes, revision `50dae0cb8c7b39dda477966eff7150cd7fe206ae`, SHA-256 `334745b8bc7fd372b1c213516f0b6338bb827b1a2abb3e77ad35fe6fea5cd16b`.

LiteRT's device logs report:

- `encode`: 1,790 / 1,790 nodes replaced by the `LITERT_CL` delegate;
- `decode`: 163 / 163;
- `decode_1`: 63 / 63.

GPU mode requests GPU alone; it does not request CPU fallback. Successful full delegation, actual Italian token emission and finite selected logits establish execution beyond merely discovering a GPU library.

The input is the user's YouTube clip, seconds 28–58, split into six **non-overlapping five-second windows**. Features are computed on the host using the sample's preemphasis, 512-point STFT and per-feature sample normalization, with librosa 1.0.0 / NumPy 2.5.3. Two complete inference passes follow compilation. Timing includes device feature-file reads, buffer writes/transfers, encoder and greedy decoder execution, token extraction and diagnostic logging. It excludes feature generation, audio decoding, loading/compilation and Cassini packaging. CPU uses LiteRT defaults, not sherpa's two-thread configuration.

Initial paired measurements:

| Execution | Load / compile | First 30-second pass | Second pass | Second-pass speed |
| --- | ---: | ---: | ---: | ---: |
| LiteRT CPU | 1.02 s | 3.45 s | 3.44 s | 8.72× realtime |
| LiteRT GPU, all model subgraphs | 5.38 s | 6.52 s | 5.68 s | 5.28× realtime |
| GPU encoder + CPU decoder | 6.72 s | 3.10 s | 3.05 s | 9.83× realtime |

The checked-in reproduction script also passed all three modes: CPU 3.55 / 3.38 seconds (8.88× on the second pass), GPU 6.32 / 6.34 seconds (4.73×), and hybrid 3.35 / 2.99 seconds (10.02×). Compile times were 1.21 / 5.55 / 6.42 seconds respectively. Token IDs again match CPU, with a maximum 80 ms timestamp difference. The same input feature bytes were used in both experiments. The script removed the probe and its 614 MB weights afterward.

The hybrid probe creates two compiled instances and copies the encoded features back to CPU once per window. This duplicates runtime/model allocations and is not yet a production memory design. Per-stage GPU submission timings are not a reliable isolated kernel benchmark; use complete pass timings, which include synchronization and transfers.

All 181 emitted token IDs match CPU across both GPU modes on this clip. Five token timestamps differ by one encoder frame (80 ms); the others match. The full GPU output is repeatable across its two passes. This is agreement on one clip, **not** an Italian WER evaluation or parity with NeMo/Cassini's repaired frontend.

A related [Mali INT8 rank-3 broadcast issue](https://github.com/google-ai-edge/LiteRT/issues/9277) was reproduced by another author using the **Japanese** hybrid Parakeet model. It persists in their LiteRT 2.2.0 retest and is now tracked under [#10445](https://github.com/google-ai-edge/LiteRT/issues/10445); lower-rank layouts need a rank-4 reshape workaround. Our v3 conversion compiles and runs successfully, so that report is not evidence that all quantized Parakeet models fail on Mali. It remains a reason to validate outputs, not just compilation.

These are exploratory runs without thermal/power controls. The hybrid improvement over CPU is small; compiler startup can outweigh it for one short recording. The five-second conversion also changes context relative to the current full-clip sherpa path. Production integration needs overlapping windows, reliable word alignment at seams, bounded memory, measured full-precision options, longer Italian tests and cold/warm latency and quality comparisons. GPU availability alone should never determine the automatic backend.

## Reproduce and integration decision

See [the isolated probe](../experiments/litert-gpu-probe/README.md). It pins/checks the runtime and model downloads, runs CPU/GPU/hybrid, retains local reports and delegate logs, compares token IDs/timestamps, then removes only the disposable probe APK and its weights. Cassini's downloads/documents stay intact.

**Decision:** keep the current sherpa CPU backend stable. The next useful prototype would be a LiteRT adapter behind the existing `PcmAudio → Transcript → CassiniDocument` pipeline, with separate encoder/decoder artifacts or explicit signature placement to avoid duplicate model allocations. Choose its backend from measured latency, memory and Italian quality. QVAC's Mali Vulkan path is a credible second experiment if LiteRT's decoder/transfer overhead remains limiting. Neither requires changing Cassini's document/viewer format.
