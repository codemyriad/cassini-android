// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#if defined(__aarch64__)
#include <sys/auxv.h>
#endif
#include "onnxruntime_c_api.h"

static void *needle_library, *ort_library;
static jobject model_owner, vad_owner; // JNI roots keep mappings alive even after Java/R8 last-use analysis.
static int (*recognize)(const float *, int, const char *, const char *, int, char *, int);
static const char *(*needle_error)(void);
static const OrtApi *api;
static OrtEnv *ort_env;
static OrtSession *session;
static OrtMemoryInfo *memory;
static float state[256], context[64];

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message ? message : "Whistle runtime failed");
}
static int check(JNIEnv *env, OrtStatus *status) {
    if (!status) return 1;
    fail(env, api->GetErrorMessage(status)); api->ReleaseStatus(status); return 0;
}
static int privacy(JNIEnv *env) {
    const char *names[] = {"NEEDLE_TELEMETRY", "DO_NOT_TRACK", "ORT_DISABLE_TELEMETRY"};
    const char *values[] = {"0", "1", "1"};
    for (int i = 0; i < 3; ++i) {
        const char *value = getenv(names[i]);
        if (!value || strcmp(value, values[i])) { fail(env, "Privacy guard before neural load"); return 0; }
    }
#if defined(__aarch64__)
    if (!(getauxval(AT_HWCAP) & (1UL << 20))) { fail(env, "Whistle requires reported dot-product support"); return 0; }
#else
    fail(env, "Whistle unavailable for this process architecture"); return 0;
#endif
    return 1;
}
JNIEXPORT void JNICALL Java_io_codemyriad_cassini_engine_whistle_WhistleNative_open(
        JNIEnv *env, jobject self, jstring needle_path, jstring ort_path, jobject model, jobject vad) {
    (void)self;
    if (!privacy(env)) return;
    if (needle_library || ort_library) { fail(env, "One load per private worker process"); return; }
    void *weights = (*env)->GetDirectBufferAddress(env, model);
    void *vad_bytes = (*env)->GetDirectBufferAddress(env, vad);
    if (!weights || !vad_bytes || (*env)->GetDirectBufferCapacity(env, model) != 16919407 ||
            (*env)->GetDirectBufferCapacity(env, vad) != 2327524) { fail(env, "Unexpected mapped model size"); return; }
    model_owner = (*env)->NewGlobalRef(env, model);
    if (!model_owner) return;
    vad_owner = (*env)->NewGlobalRef(env, vad);
    if (!vad_owner) return;
    const char *path = (*env)->GetStringUTFChars(env, needle_path, NULL);
    if (!path) return;
    needle_library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, needle_path, path);
    if (!needle_library) { fail(env, dlerror()); return; }
    int (*load)(const unsigned char *, unsigned long long) = dlsym(needle_library, "needle_load");
    int (*models)(void) = dlsym(needle_library, "needle_models");
    recognize = dlsym(needle_library, "needle_transcribe");
    needle_error = dlsym(needle_library, "needle_last_error");
    if (!load || !models || !recognize || !needle_error || models() != 0) { fail(env, "Unexpected Needle C ABI/state"); return; }
    if (load(weights, 16919407) < 0 || models() != 2) { fail(env, needle_error()); return; }
    path = (*env)->GetStringUTFChars(env, ort_path, NULL);
    if (!path) return;
    ort_library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, ort_path, path);
    if (!ort_library) { fail(env, dlerror()); return; }
    const OrtApiBase *(*base)(void) = dlsym(ort_library, "OrtGetApiBase");
    if (!base || !(api = base()->GetApi(ORT_API_VERSION))) { fail(env, "Unexpected private ORT API"); return; }
    OrtSessionOptions *options = NULL;
    if (!check(env, api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "cassini-whistle", &ort_env))) return;
    if (!check(env, api->DisableTelemetryEvents(ort_env))) return;
    if (!check(env, api->CreateSessionOptions(&options))) return;
    int ok = check(env, api->SetIntraOpNumThreads(options, 1)) && check(env, api->SetInterOpNumThreads(options, 1)) &&
        check(env, api->CreateSessionFromArray(ort_env, vad_bytes, 2327524, options, &session));
    api->ReleaseSessionOptions(options);
    if (!ok) return;
    if (!check(env, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory))) return;
    // Both mapped model buffers, libraries and runtime state remain alive until this private process exits.
}
JNIEXPORT jfloatArray JNICALL Java_io_codemyriad_cassini_engine_whistle_WhistleNative_vadState(
        JNIEnv *env, jobject self) {
    (void)self;
    if (!session) { fail(env, "VAD is not loaded"); return NULL; }
    jfloatArray result = (*env)->NewFloatArray(env, 320);
    if (result) {
        (*env)->SetFloatArrayRegion(env, result, 0, 256, state);
        if (!(*env)->ExceptionCheck(env)) (*env)->SetFloatArrayRegion(env, result, 256, 64, context);
    }
    return result;
}
JNIEXPORT void JNICALL Java_io_codemyriad_cassini_engine_whistle_WhistleNative_restoreVad(
        JNIEnv *env, jobject self, jfloatArray saved) {
    (void)self;
    if (!session || (*env)->GetArrayLength(env, saved) != 320) { fail(env, "Invalid VAD state shape"); return; }
    float values[320];
    (*env)->GetFloatArrayRegion(env, saved, 0, 320, values);
    if ((*env)->ExceptionCheck(env)) return;
    for (int i = 0; i < 320; ++i) if (!isfinite(values[i])) { fail(env, "Invalid VAD state value"); return; }
    memcpy(state, values, sizeof state);
    memcpy(context, values + 256, sizeof context);
}
JNIEXPORT jfloat JNICALL Java_io_codemyriad_cassini_engine_whistle_WhistleNative_probability(
        JNIEnv *env, jobject self, jfloatArray frame) {
    (void)self;
    if (!session || (*env)->GetArrayLength(env, frame) != 512) { fail(env, "Expected loaded VAD and 512 samples"); return 0; }
    float input[576]; memcpy(input, context, sizeof context);
    (*env)->GetFloatArrayRegion(env, frame, 0, 512, input + 64);
    if ((*env)->ExceptionCheck(env)) return 0;
    int64_t rate = 16000, shape[] = {1,576}, state_shape[] = {2,1,128};
    OrtValue *inputs[3] = {0}, *outputs[2] = {0};
    const char *in_names[] = {"input","state","sr"}, *out_names[] = {"output","stateN"};
    float probability = 0;
#define CHECK(x) if (!check(env, (x))) goto done
    CHECK(api->CreateTensorWithDataAsOrtValue(memory, input, sizeof input, shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputs[0]));
    CHECK(api->CreateTensorWithDataAsOrtValue(memory, state, sizeof state, state_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &inputs[1]));
    CHECK(api->CreateTensorWithDataAsOrtValue(memory, &rate, sizeof rate, NULL, 0, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputs[2]));
    CHECK(api->Run(session, NULL, in_names, (const OrtValue *const *)inputs, 3, out_names, 2, outputs));
    void *p = NULL, *s = NULL;
    CHECK(api->GetTensorMutableData(outputs[0], &p)); CHECK(api->GetTensorMutableData(outputs[1], &s));
    probability = *(float *)p; memcpy(state, s, sizeof state); memcpy(context, input + 512, sizeof context);
    if (!isfinite(probability) || probability < 0 || probability > 1) fail(env, "Invalid VAD probability");
    done: for (int i = 0; i < 3; ++i) if (inputs[i]) api->ReleaseValue(inputs[i]);
    for (int i = 0; i < 2; ++i) if (outputs[i]) api->ReleaseValue(outputs[i]);
    return probability;
#undef CHECK
}
JNIEXPORT jbyteArray JNICALL Java_io_codemyriad_cassini_engine_whistle_WhistleNative_transcribe(
        JNIEnv *env, jobject self, jfloatArray samples) {
    (void)self;
    jsize count = (*env)->GetArrayLength(env, samples);
    if (!session || count < 1 || count > 240512) { fail(env, "Expected loaded worker and bounded segment"); return NULL; }
    float *pcm = malloc((size_t)count * sizeof(float));
    char *json = calloc(262144, 1);
    if (!pcm || !json) { free(pcm); free(json); fail(env, "Segment allocation failed"); return NULL; }
    (*env)->GetFloatArrayRegion(env, samples, 0, count, pcm);
    if ((*env)->ExceptionCheck(env)) { free(pcm); free(json); return NULL; }
    int result = recognize(pcm, count, NULL, NULL, 1, json, 262144);
    free(pcm);
    if (result < 0) { free(json); fail(env, needle_error()); return NULL; }
    size_t length = strnlen(json, 262144);
    if (length == 262144) { free(json); fail(env, "Unterminated recognition result"); return NULL; }
    jbyteArray output = (*env)->NewByteArray(env, (jsize)length);
    if (output) (*env)->SetByteArrayRegion(env, output, 0, (jsize)length, (jbyte *)json);
    free(json); return output;
}
