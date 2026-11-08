// SPDX-License-Identifier: GPL-3.0-or-later
// Lab JNI bootstrap: deliberately has no DT_NEEDED edge to Needle or ORT.
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

static void *library;
static unsigned char *weights; // Needle retains model memory; retain until this dedicated process exits.
static int (*transcribe)(const float *, int, const char *, const char *, int, char *, int);
static const char *(*last_error)(void);

static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}

JNIEXPORT void JNICALL Java_io_codemyriad_cassini_whistlespike_Spike_load(
        JNIEnv *env, jclass type, jstring path, jbyteArray model) {
    (void)type;
    const char *names[] = {"NEEDLE_TELEMETRY", "DO_NOT_TRACK", "ORT_DISABLE_TELEMETRY"};
    const char *values[] = {"0", "1", "1"};
    for (int i = 0; i < 3; ++i) {
        const char *value = getenv(names[i]);
        if (!value || strcmp(value, values[i])) { fail(env, "Privacy guard before dlopen"); return; }
    }
    if (library) { fail(env, "Only one model load per lab process"); return; }
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return;
    void *candidate = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (!candidate) { fail(env, dlerror()); return; }
    int (*load)(const unsigned char *, unsigned long long) = dlsym(candidate, "needle_load");
    int (*models)(void) = dlsym(candidate, "needle_models");
    transcribe = dlsym(candidate, "needle_transcribe");
    last_error = dlsym(candidate, "needle_last_error");
    if (!load || !models || !transcribe || !last_error || models() != 0) {
        dlclose(candidate); fail(env, "Unexpected C ABI or initial model state"); return;
    }
    jsize size = (*env)->GetArrayLength(env, model);
    if (size != 16919407) { dlclose(candidate); fail(env, "Unexpected model size"); return; }
    weights = malloc((size_t)size);
    if (!weights) { dlclose(candidate); fail(env, "Model allocation failed"); return; }
    (*env)->GetByteArrayRegion(env, model, 0, size, (jbyte *)weights);
    if ((*env)->ExceptionCheck(env)) { free(weights); weights = NULL; dlclose(candidate); return; }
    // After entering the engine, conservatively keep the library and buffer until process death,
    // even on failure. The public C API has no explicit unload ownership contract.
    library = candidate;
    if (load(weights, (unsigned long long)size) < 0 || models() != 2) {
        fail(env, last_error());
    }
}

JNIEXPORT jbyteArray JNICALL Java_io_codemyriad_cassini_whistlespike_Spike_transcribe(
        JNIEnv *env, jclass type, jbyteArray pcm) {
    (void)type;
    if (!library) { fail(env, "Model not loaded"); return NULL; }
    jsize size = (*env)->GetArrayLength(env, pcm);
    if (size <= 0 || size % 4 || size > 30 * 16000 * 4) {
        fail(env, "Expected at most 30 seconds of float32 LE PCM"); return NULL;
    }
    float *input = malloc((size_t)size);
    char *output = calloc(1048576, 1);
    if (!input || !output) { free(input); free(output); fail(env, "PCM/result allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env, pcm, 0, size, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) { free(input); free(output); return NULL; }
    int count = transcribe(input, size / 4, NULL, NULL, 1, output, 1048576);
    free(input);
    if (count < 0) { free(output); fail(env, last_error()); return NULL; }
    size_t length = strnlen(output, 1048576);
    if (length == 1048576) { free(output); fail(env, "Unterminated engine result"); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (jbyte *)output);
    free(output);
    return result;
}
