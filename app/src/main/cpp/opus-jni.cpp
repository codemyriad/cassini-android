#include <jni.h>
#include <opus.h>
#include <cstring>
#include <vector>

static void fail(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jlong JNICALL Java_org_cassini_android_NativeOpus_create(JNIEnv *env, jobject) {
  int error;
  auto *enc = opus_encoder_create(16000, 1, OPUS_APPLICATION_AUDIO, &error);
  if (error != OPUS_OK || !enc) { fail(env, "Cannot create Opus encoder"); return 0; }
  opus_encoder_ctl(enc, OPUS_SET_BITRATE(24000));
  opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
  return reinterpret_cast<jlong>(enc);
}
extern "C" JNIEXPORT jint JNICALL Java_org_cassini_android_NativeOpus_lookahead(JNIEnv *, jobject, jlong ptr) {
  int samples = 0; opus_encoder_ctl(reinterpret_cast<OpusEncoder *>(ptr), OPUS_GET_LOOKAHEAD(&samples)); return samples;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_cassini_android_NativeOpus_encode(JNIEnv *env, jobject, jlong ptr, jfloatArray pcm) {
  if (env->GetArrayLength(pcm) != 320) { fail(env, "Opus needs a 20 ms frame"); return nullptr; }
  float samples[320]; unsigned char packet[4000];
  env->GetFloatArrayRegion(pcm, 0, 320, samples);
  if (env->ExceptionCheck()) return nullptr;
  int n = opus_encode_float(reinterpret_cast<OpusEncoder *>(ptr), samples, 320, packet, sizeof(packet));
  if (n < 0) { fail(env, opus_strerror(n)); return nullptr; }
  auto out = env->NewByteArray(n);
  if (out) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte *>(packet));
  return out;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_cassini_android_NativeOpus_save(JNIEnv *env, jobject, jlong ptr) {
  int n = opus_encoder_get_size(1);
  auto out = env->NewByteArray(n);
  if (out) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte *>(ptr));
  return out;
}
extern "C" JNIEXPORT void JNICALL Java_org_cassini_android_NativeOpus_restore(JNIEnv *env, jobject, jlong ptr, jbyteArray state) {
  if (env->GetArrayLength(state) != opus_encoder_get_size(1)) { fail(env, "Incompatible Opus checkpoint"); return; }
  env->GetByteArrayRegion(state, 0, opus_encoder_get_size(1), reinterpret_cast<jbyte *>(ptr));
}
extern "C" JNIEXPORT void JNICALL Java_org_cassini_android_NativeOpus_destroy(JNIEnv *, jobject, jlong ptr) {
  opus_encoder_destroy(reinterpret_cast<OpusEncoder *>(ptr));
}
