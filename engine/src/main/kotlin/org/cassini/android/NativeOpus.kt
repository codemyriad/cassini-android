package org.cassini.android

/**
 * libopus encoder state holds absolute pointers into the library's static tables, so it is never
 * saved: a resume primes a fresh encoder with the frames encoded last instead.
 *
 * The class keeps its 0.1.0 package and name: libcassini_opus binds `Java_org_cassini_android_NativeOpus_*`.
 */
internal object NativeOpus {
    init { System.loadLibrary("cassini_opus") }
    external fun create(): Long
    external fun lookahead(ptr: Long): Int
    external fun encode(ptr: Long, frame: FloatArray): ByteArray
    external fun destroy(ptr: Long)
}
