package org.cassini.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.cassini.core.asr.BatteryProjection

/** The Android half of [BatteryProjection]: the sticky battery broadcast and the fuel gauge. */
internal object BatteryReader {
    fun read(context: Context, elapsedMs: Long): BatteryProjection.Reading? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val manager = context.getSystemService(BatteryManager::class.java)
        val charge = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 }
        return BatteryProjection.Reading(elapsedMs, (level * 100 / scale).coerceIn(0, 100),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0, charge)
    }
}
