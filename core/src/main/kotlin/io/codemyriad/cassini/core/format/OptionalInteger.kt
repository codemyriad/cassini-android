package io.codemyriad.cassini.core.format

import org.json.JSONObject
import java.math.BigDecimal

const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** Optional JSON integer, without org.json's string coercion, fractional truncation or Int overflow. */
fun JSONObject.optionalInteger(key: String, maximum: Long = MAX_SAFE_INTEGER): Long? {
    return try {
        (opt(key) as? Number)?.let { BigDecimal(it.toString()).longValueExact() }?.takeIf { it in 0..maximum }
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}
