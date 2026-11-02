package io.codemyriad.cassini

/**
 * libopus encoder state holds absolute pointers into the library's static tables, so it is never
 * saved: a resume primes a fresh encoder with the frames encoded last instead.
 *
 * libcassini_opus binds `Java_io_codemyriad_cassini_NativeOpus_*`, so the package and name must match it.
 */
internal object NativeOpus {
    init { System.loadLibrary("cassini_opus") }
    external fun create(): Long
    external fun lookahead(ptr: Long): Int
    external fun encode(ptr: Long, frame: FloatArray): ByteArray
    external fun destroy(ptr: Long)
}
