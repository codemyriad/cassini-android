package io.codemyriad.cassini.core.recorder

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection, no final XOR), as the recorder firmware computes it
 * over each GETS frame payload and each PUT chunk. `"123456789"` gives 0x29B1; no bytes give 0xFFFF.
 */
object Crc16CcittFalse {
    private const val INITIAL = 0xFFFF
    private const val POLYNOMIAL = 0x1021
    private const val TOP_BIT = 0x8000
    private const val MASK = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val BITS = 8

    /** The CRC of [length] bytes of [data] from [offset], as an unsigned value in 0..0xFFFF. */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Range outside the array" }
        var crc = INITIAL
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and BYTE_MASK) shl BITS)
            repeat(BITS) {
                crc = if (crc and TOP_BIT != 0) (crc shl 1) xor POLYNOMIAL else crc shl 1
            }
            crc = crc and MASK
        }
        return crc
    }
}
