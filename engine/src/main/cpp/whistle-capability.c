#include <jni.h>
#if defined(__aarch64__)
#include <errno.h>
#include <sys/auxv.h>
#endif

// Independent of Needle and its initialization. Never execute or force an optional instruction here.
JNIEXPORT jint JNICALL
Java_io_codemyriad_cassini_engine_asr_WhistleDeviceCapability_probe(JNIEnv *env, jobject instance) {
    (void)env;
    (void)instance;
#if defined(__aarch64__)
    errno = 0;
    const unsigned long hwcap = getauxval(AT_HWCAP);
    if (errno != 0) return -2;
    // Linux arm64 UAPI HWCAP_ASIMDDP. Use the same bit the pinned Needle constructor tests.
    return (hwcap & (1UL << 20)) != 0 ? 1 : 0;
#else
    return -1;
#endif
}
