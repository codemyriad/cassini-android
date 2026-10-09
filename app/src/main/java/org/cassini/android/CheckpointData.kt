package org.cassini.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject

internal object CheckpointData {
    fun bytes(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    fun bytes(value: String): ByteArray {
        require(value.length <= 4 * 1024 * 1024)
        return Base64.getDecoder().decode(value)
    }
    fun floats(value: FloatArray): String {
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        value.forEach(buffer::putFloat)
        return bytes(buffer.array())
    }
    fun floats(value: String, maximum: Int): FloatArray {
        val data = bytes(value)
        require(data.size % 4 == 0 && data.size / 4 <= maximum)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(data.size / 4) { buffer.float }
    }
    fun strings(value: JSONObject) = value.keys().asSequence().associateWith { value.getString(it) }
}
