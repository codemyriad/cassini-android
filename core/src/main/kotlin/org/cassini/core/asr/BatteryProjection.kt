package org.cassini.core.asr

import kotlin.math.floor

/** Observes only active processing. Starting or resuming a job starts a fresh discharge measurement. */
class BatteryProjection {
    data class Reading(val elapsedMs: Long, val percent: Int, val charging: Boolean, val chargeUah: Long? = null)
    data class Estimate(val percentNow: Int?, val percentAfter: Int?, val charging: Boolean)
    private var baseline: Reading? = null
    fun sample(now: Reading, remainingMs: Long?): Estimate {
        require(now.percent in 0..100)
        if (now.charging) { baseline = null; return Estimate(now.percent, null, true) }
        val first = baseline
        if (first == null || now.elapsedMs < first.elapsedMs || now.percent > first.percent ||
            (now.chargeUah != null && first.chargeUah != null && now.chargeUah > first.chargeUah)) baseline = now
        val start = baseline!!
        val elapsed = now.elapsedMs - start.elapsedMs
        val drop = if (start.chargeUah != null && now.chargeUah != null && start.chargeUah > 0 && start.percent > 0)
            (start.chargeUah - now.chargeUah).toDouble() * start.percent / start.chargeUah
            else (start.percent - now.percent).toDouble()
        // Fuel gauges and percentage steps are noisy. Do not extrapolate a few seconds of readings.
        val predicted = if (elapsed >= 120000 && drop >= .5 && remainingMs != null && remainingMs > 0)
            floor(now.percent - drop * remainingMs / elapsed).toInt().coerceIn(0, now.percent) else null
        return Estimate(now.percent, predicted, false)
    }
}
