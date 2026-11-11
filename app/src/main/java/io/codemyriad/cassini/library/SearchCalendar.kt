package io.codemyriad.cassini.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.ZoneId

/** Register before the retained read, so midnight or a clock/zone change cannot fall between them. */
internal fun searchCalendar(context: Context): Flow<ZoneId> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            trySend(ZoneId.systemDefault())
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(ZoneId.systemDefault())
    awaitClose { context.unregisterReceiver(receiver) }
}
