#include <jni.h>
#include <opus.h>
#include <cstring>
#include <vector>

// Throws IllegalStateException unless an exception is already pending. If FindClass fails it has
// already thrown (NoClassDefFoundError or OutOfMemoryError), which then reaches the caller instead.
static void fail(JNIEnv *env, const char *message) {
  if (env->ExceptionCheck()) return;
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type == nullptr) return;
  env->ThrowNew(type, message);
  env->DeleteLocalRef(type);
}
// A zero handle means the encoder was never created or was already destroyed.
static OpusEncoder *encoder(JNIEnv *env, jlong ptr) {
  if (ptr == 0) fail(env, "Opus encoder is not open");
  return reinterpret_cast<OpusEncoder *>(ptr);
}
extern "C" JNIEXPORT jlong JNICALL Java_org_cassini_android_NativeOpus_create(JNIEnv *env, jobject) {
  int error;
  auto *enc = opus_encoder_create(16000, 1, OPUS_APPLICATION_AUDIO, &error);
  if (error != OPUS_OK || !enc) { fail(env, "Cannot create Opus encoder"); return 0; }
  // A resumed encoder must use the settings of the stream it continues: 24 kb/s, complexity 5.
  int status = opus_encoder_ctl(enc, OPUS_SET_BITRATE(24000));
  if (status == OPUS_OK) status = opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
  if (status != OPUS_OK) { opus_encoder_destroy(enc); fail(env, opus_strerror(status)); return 0; }
  return reinterpret_cast<jlong>(enc);
}
extern "C" JNIEXPORT jint JNICALL Java_org_cassini_android_NativeOpus_lookahead(JNIEnv *env, jobject, jlong ptr) {
  auto *enc = encoder(env, ptr);
  if (!enc) return 0;
  int samples = 0;
  int status = opus_encoder_ctl(enc, OPUS_GET_LOOKAHEAD(&samples));
  if (status != OPUS_OK) { fail(env, opus_strerror(status)); return 0; }
  return samples;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_cassini_android_NativeOpus_encode(JNIEnv *env, jobject, jlong ptr, jfloatArray pcm) {
  auto *enc = encoder(env, ptr);
  if (!enc) return nullptr;
  if (env->GetArrayLength(pcm) != 320) { fail(env, "Opus needs a 20 ms frame"); return nullptr; }
  float samples[320]; unsigned char packet[4000];
  env->GetFloatArrayRegion(pcm, 0, 320, samples);
  if (env->ExceptionCheck()) return nullptr;
  int n = opus_encode_float(enc, samples, 320, packet, sizeof(packet));
  if (n < 0) { fail(env, opus_strerror(n)); return nullptr; }
  auto out = env->NewByteArray(n);
  if (out) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte *>(packet));
  return out;
}
extern "C" JNIEXPORT void JNICALL Java_org_cassini_android_NativeOpus_destroy(JNIEnv *, jobject, jlong ptr) {
  if (ptr == 0) return;
  opus_encoder_destroy(reinterpret_cast<OpusEncoder *>(ptr));
}
