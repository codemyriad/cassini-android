package io.codemyriad.cassini.core.format

import org.json.JSONObject
import java.math.BigDecimal

const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** Optional JSON integer, without org.json's string coercion, fractional truncation or Int overflow. */
fun JSONObject.optionalInteger(key: String, maximum: Long = MAX_SAFE_INTEGER): Long? {
    val value = opt(key) as? Number ?: return null
    val integer = try {
        BigDecimal(value.toString()).longValueExact()
    } catch (_: ArithmeticException) {
        return null
    } catch (_: NumberFormatException) {
        return null
    }
    return integer.takeIf { it in 0..maximum }
}
