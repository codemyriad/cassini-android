package io.codemyriad.cassini.core.recorder

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The recorder's binding key and challenge response. The app binds with a random 32-byte key (`PAIR`); later sessions
 * answer the 16-byte nonce of `CHAL` with HMAC-SHA256(key, nonce bytes) as 64 lower-case hex digits (`AUTH`).
 */
object XrecAuth {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 16
    private const val ALGORITHM = "HmacSHA256"
    private const val HEX_RADIX = 16
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xF
    private const val HEX_DIGITS = "0123456789abcdef"

    /** A new binding key from [random]. */
    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** The `AUTH` response to the hex [nonceHex] of a `CHAL` reply, computed over the decoded nonce bytes. */
    fun response(key: ByteArray, nonceHex: String): String {
        require(key.size == KEY_BYTES) { "The key has ${key.size} bytes, not $KEY_BYTES" }
        val nonce = fromHex(nonceHex)
        require(nonce.size == NONCE_BYTES) { "The nonce has ${nonce.size} bytes, not $NONCE_BYTES" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return toHex(mac.doFinal(nonce))
    }

    /** Lower-case hex of [bytes]. */
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt()
            append(HEX_DIGITS[(value shr NIBBLE_BITS) and NIBBLE_MASK])
            append(HEX_DIGITS[value and NIBBLE_MASK])
        }
    }

    /** The bytes of an even-length hex string (either case). */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[2 * i], HEX_RADIX)
            val low = Character.digit(hex[2 * i + 1], HEX_RADIX)
            require(high >= 0 && low >= 0) { "Not a hex digit" }
            ((high shl NIBBLE_BITS) or low).toByte()
        }
    }
}
