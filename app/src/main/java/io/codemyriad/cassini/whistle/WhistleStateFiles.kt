package io.codemyriad.cassini.whistle

import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.codemyriad.cassini.engine.whistle.WhistleStateCodec
import io.codemyriad.cassini.engine.whistle.WhistleStream

/** Only caller-supplied descriptors. The isolated process never opens a parent work-directory path. */
internal object WhistleStateFiles {
    fun read(data: Bundle): WhistleStream.State? = descriptor(data, "restore")?.use { fd ->
        require(fd.statSize in 1..WhistleStateCodec.MAX_BYTES.toLong())
        ParcelFileDescriptor.AutoCloseInputStream(fd).use(WhistleStateCodec::read)
    }

    fun write(data: Bundle, state: WhistleStream.State) {
        requireNotNull(descriptor(data, "snapshot")).use { fd ->
            require(fd.statSize in 0..WhistleStateCodec.MAX_BYTES.toLong())
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { target ->
                target.channel.truncate(0)
                target.channel.position(0)
                WhistleStateCodec.write(target, state)
                target.fd.sync()
            }
        }
    }

    @Suppress("DEPRECATION") // The typed overload is API33; this private protocol also runs on API29.
    private fun descriptor(data: Bundle, key: String): ParcelFileDescriptor? = data.getParcelable(key)
}
