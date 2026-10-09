package org.cassini.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.floor

/** Observes only active processing. Starting or resuming a job starts a fresh discharge measurement. */
internal class BatteryProjection {
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
    companion object {
        fun read(context: Context, elapsedMs: Long): Reading? {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val manager = context.getSystemService(BatteryManager::class.java)
            val charge = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 }
            return Reading(elapsedMs, (level * 100 / scale).coerceIn(0, 100),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0, charge)
        }
    }
}
