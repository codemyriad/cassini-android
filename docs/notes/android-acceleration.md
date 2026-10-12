# Android Parakeet acceleration investigation — 2026-10-01

> Historical note: this file records work on the 0.1.0 prototype, written from 2026-10-01 to 2026-10-09. Nobody updates it. Its statements about the app can be out of date. For the current app, read [the architecture](../architecture.md) and [the development notes](../development.md).

**GPU execution is possible on the connected Pixel 8, also with a quantized Parakeet TDT 0.6B v3 model.** An isolated LiteRT experiment compiled all three subgraphs of the model for OpenCL and transcribed the supplied Italian recording. This needs a model conversion and a runtime that are different from the current sherpa AAR. In this experiment, the full GPU execution was slower than the LiteRT CPU. A GPU encoder with a CPU decoder was a little faster after the load.

The production app continues to use the sherpa CPU and to record CPU in the Cassini provenance. The app does not select an experimental backend automatically.

## Existing work and branches

| Candidate | What actually exists | Applicability |
| --- | --- | --- |
| [benniekiss/sherpa-onnx, `webgpu`](https://github.com/benniekiss/sherpa-onnx/tree/webgpu) | Native registration of the ONNX Runtime WebGPU provider and a build flag. [Discussion](https://github.com/k2-fsa/sherpa-onnx/issues/3665). | The [CMake package](https://github.com/benniekiss/sherpa-onnx/blob/c8f2f33cd0c82bcded0819d34e0475c74b710866/cmake/onnxruntime-webgpu.cmake) of the branch accepts Linux x64, macOS arm64 and Windows x64/arm64. It explicitly refuses other systems. No Android arm64 binary, no Android CI and no Parakeet v3 result on a phone. It is not a drop-in replacement for Android. |
| [Google LiteRT ASR Android sample](https://github.com/google-ai-edge/litert-samples/tree/385cfe0d09eeeb71f62daafd35ef6f353718720e/samples/litert/speech_recognition) | An official Kotlin Android runner, a TDT decoder, conversion and verification tools, GPU support for multilingual TDT v3, and [converted models](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/tree/50dae0cb8c7b39dda477966eff7150cd7fe206ae). | **Tested on this Pixel**, with LiteRT 2.2.0 and the 5-second stateful INT8 conversion. The published Google Tensor NPU files are for G5/G6, not for the G3 of this phone. |
| [QVAC fabric speech / Parakeet ggml](https://github.com/tetherto/qvac-fabric-speech.cpp/tree/6b6f2bdefbecd0fee6bc51115006b336761478f5/engines/parakeet) | TDT v3 GGUF Q8/F16 support, a GPU encoder and graph decoding. The [Android package](https://github.com/tetherto/qvac/blob/03d764c6f998cc21335289dd3ac8df4095a853f0/packages/asr-ggml/README.md) supports Android arm64 12+ with Vulkan/OpenCL backends. | Its [backend policy](https://github.com/tetherto/qvac-fabric-speech.cpp/blob/6b6f2bdefbecd0fee6bc51115006b336761478f5/engines/parakeet/docs/backends.md) selects Mali Vulkan for the Parakeet encoder and the TDT computation, with a CPU fallback for each operation. A promising independent path. We did not build or measure it. Adreno OpenCL results are not Pixel results. |
| [mudler/parakeet.cpp](https://github.com/mudler/parakeet.cpp) | Multilingual TDT v3 GGUF support and a Vulkan build option. | A C++/ggml alternative. Its published prebuilt targets do not give an Android Parakeet app or runtime. It needs an Android cross-build and a validation. We did not measure it. |
| [surma/parakeeb](https://github.com/surma/parakeeb#onnx-runtime-execution-providers) | Android TDT v3 ONNX with XNNPACK/NNAPI experiments. | It reports that `nnapi-fp16,cpu` causes a Darwinn/TPU compilation on the Pixel 8a, but with a high initialization latency and a slower transcription than XNNPACK. This is a measurement of another author, not ours, and TPU execution is not GPU execution. |

There is also a [soniqo Android SDK](https://github.com/soniqo/speech-android) and a [LiteRT Parakeet implementation](https://github.com/soniqo/speech-core/blob/f0050757a7d7d24c60b17e60016989ecd07a491d/src/models/litert/litert_parakeet_stt.cpp). They show that other integration work exists. Their Android/NNAPI marketing does not show a GPU speed-up for v3 on a Pixel. The smaller English EOU/CTC models do not replace our Italian TDT v3 target.

## Why a change of the current provider does not enable the GPU

The bundled sherpa-onnx 1.13.7 uses ONNX Runtime. Its [provider dispatch](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/csrc/provider.cc) has no generic Android Vulkan/OpenCL GPU provider. CUDA is for NVIDIA hardware, and XNNPACK is CPU acceleration.

The build compiles its [NNAPI setup](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/csrc/session.cc) only when `__ANDROID_API__ >= 27`. An inspection of the bundled arm64 JNI/C API binaries finds the API-27 refusal message and no messages of an enabled NNAPI branch. Their ONNX Runtime library contains the NNAPI provider. This is a limit of the wrapper build. A higher minSdk of the app alone does not change that compiled library.

The Pixel gives `android.hardware.neuralnetworks.IDevice/google-edgetpu`. A new build of the wrapper can enable a separate NNAPI/TPU experiment. But it does not guarantee the coverage of the graph, a useful performance or the selection of the GPU. The Parakeeb result makes this path less attractive than the LiteRT route, which we showed directly.

INT8 is not CPU-only by nature. Our successful LiteRT run used quantized weights. The arithmetic and layout of the accelerator and the supported graph operations are important. This does **not** mean that LiteRT can load the existing ONNX files, or that each GPU operation uses integer arithmetic. The probe keeps the default GPU precision of LiteRT and does not force FP32 computation.

## Pixel experiment and evidence

Device: Pixel 8 / Tensor G3 / Android 17. Runtime: LiteRT 2.2.0, in a separate `org.cassini.probe` APK. Model: `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite`, 614,261,072 bytes, revision `50dae0cb8c7b39dda477966eff7150cd7fe206ae`, SHA-256 `334745b8bc7fd372b1c213516f0b6338bb827b1a2abb3e77ad35fe6fea5cd16b`.

The device logs of LiteRT report:

- `encode`: the `LITERT_CL` delegate replaced 1,790 / 1,790 nodes.
- `decode`: 163 / 163.
- `decode_1`: 63 / 63.

The GPU mode asks only for the GPU. It does not ask for a CPU fallback. The full delegation, the real Italian token output and the finite selected logits show an execution, not only the discovery of a GPU library.

The input is the YouTube clip of the user, seconds 28–58, split into six **five-second windows that do not overlap**. The host calculates the features with the preemphasis of the sample, a 512-point STFT and a normalization for each feature, with librosa 1.0.0 / NumPy 2.5.3. Two complete inference passes come after the compilation.

The time includes the reads of the feature file on the device, and the buffer writes and transfers. It also includes the execution of the encoder and the greedy decoder, the extraction of the tokens and the diagnostic log. It does not include the feature generation, the audio decode, the load and the compilation, and the Cassini packaging. The CPU mode uses the LiteRT defaults, not the two-thread configuration of sherpa.

First paired measurements:

| Execution | Load / compile | First 30-second pass | Second pass | Second-pass speed |
| --- | ---: | ---: | ---: | ---: |
| LiteRT CPU | 1.02 s | 3.45 s | 3.44 s | 8.72× realtime |
| LiteRT GPU, all model subgraphs | 5.38 s | 6.52 s | 5.68 s | 5.28× realtime |
| GPU encoder + CPU decoder | 6.72 s | 3.10 s | 3.05 s | 9.83× realtime |

The reproduction script also passed in all three modes. The script is not in the tree at this time. See `git show 0ebea43:scripts/run-litert-gpu-probe.sh`. Its results:

- CPU: 3.55 / 3.38 seconds (8.88× on the second pass).
- GPU: 6.32 / 6.34 seconds (4.73×).
- Hybrid: 3.35 / 2.99 seconds (10.02×).

The compile times were 1.21 / 5.55 / 6.42 seconds. The token IDs again agree with the CPU, with a maximum difference of 80 ms in the timestamps. The two experiments used the same input feature bytes. At the end, the script removed the probe and its 614 MB of weights.

The hybrid probe makes two compiled instances and copies the encoded features back to the CPU one time for each window. This duplicates the runtime and model allocations, and it is not yet a memory design for production. The timings of each GPU stage are not a reliable benchmark of isolated kernels. Use the timings of complete passes, which include the synchronization and the transfers.

All 181 token IDs agree with the CPU in the two GPU modes on this clip. Five token timestamps are different by one encoder frame (80 ms), and the others agree. The full GPU output is the same in its two passes. This is agreement on one clip. It is **not** an Italian WER evaluation, and it is not parity with NeMo or with the repaired frontend of Cassini.

Another author reproduced a related [Mali INT8 rank-3 broadcast issue](https://github.com/google-ai-edge/LiteRT/issues/9277) with the **Japanese** hybrid Parakeet model. It is still there in their LiteRT 2.2.0 retest, and [#10445](https://github.com/google-ai-edge/LiteRT/issues/10445) tracks it at this time. Layouts of a lower rank need a rank-4 reshape as a workaround. Our v3 conversion compiles and runs correctly. Thus that report does not show that all quantized Parakeet models fail on Mali. It is still a reason to validate the outputs, not only the compilation.

These are exploratory runs without control of the temperature or the power. The improvement of the hybrid mode over the CPU is small, and for one short recording the compiler start can be larger. The five-second conversion also changes the context, relative to the current full-clip sherpa path.

An integration in production needs windows that overlap, a reliable word alignment at the seams and a memory limit. It also needs measured full-precision options, longer Italian tests, and comparisons of cold and warm latency and of quality. The availability of a GPU alone must never select the automatic backend.

## Reproduction and integration decision

The isolated probe is not in the tree at this time. Its last version is in commit `0ebea43` on `main` (`git show 0ebea43:experiments/litert-gpu-probe/README.md`). The tag `archive/litert-gpu-probe` also marks this commit after the owner pushes it. The probe pins and examines the runtime and model downloads, runs CPU/GPU/hybrid, keeps local reports and delegate logs, and compares the token IDs and timestamps. Then it removes only the disposable probe APK and its weights. The downloads and documents of Cassini do not change.

**Decision:** keep the current sherpa CPU backend stable. The next useful prototype is a LiteRT adapter behind the existing `PcmAudio → Transcript → CassiniDocument` pipeline. It needs separate encoder and decoder artifacts, or an explicit signature placement, to prevent duplicate model allocations. Select its backend from the measured latency, memory and Italian quality. The Mali Vulkan path of QVAC is a credible second experiment if the decoder and transfer overhead of LiteRT stays a limit. Neither experiment needs a change of the document or viewer format of Cassini.
