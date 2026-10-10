package org.cassini.android.adapters

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the active network is validated (architecture 5.6). Tests pass a fixed answer. */
fun interface NetworkCheck {
    fun validated(): Boolean

    companion object {
        /**
         * The active network has `NET_CAPABILITY_VALIDATED`: Android reached the internet over it. Without it the
         * best-effort model parts are skipped at once instead of waiting for a 30 s timeout.
         */
        fun of(context: Context): NetworkCheck {
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            return NetworkCheck {
                val network = connectivity?.activeNetwork
                val capabilities = network?.let(connectivity::getNetworkCapabilities)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        }
    }
}
