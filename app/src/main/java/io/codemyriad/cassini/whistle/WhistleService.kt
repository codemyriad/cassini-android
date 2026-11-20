package io.codemyriad.cassini.whistle

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import androidx.annotation.RequiresApi
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechSegment
import io.codemyriad.cassini.core.checkpoint.WhistleSegmentCache
import io.codemyriad.cassini.engine.whistle.WhistleAssets
import io.codemyriad.cassini.engine.whistle.WhistleNative
import io.codemyriad.cassini.engine.whistle.WhistleStream
import java.util.concurrent.Executors

/** Private process; no graph, model-store, network client or system NN runtime. Reclaimed on client unbind. */
@RequiresApi(Build.VERSION_CODES.Q)
@Suppress("TooManyFunctions") // Service lifecycle plus one helper per WhistleProtocol command.
class WhistleService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var assets: WhistleAssets? = null // Retain both read-only mappings until process exit.
    private var stream: WhistleStream? = null
    private var owner: IBinder? = null
    private var busy = false
    private var failed = false
    private val cachedSegments = HashMap<String, Pair<Int, ByteArray>>()
    private val segments = ArrayList<Bundle>()
    private val main = Handler(Looper.getMainLooper())
    private val incoming = Handler(Looper.getMainLooper()) { receive(it) }
    private val messenger = Messenger(incoming)

    private fun receive(message: Message): Boolean {
        val reply = message.replyTo ?: return true
        val sameOwner = owner == null || owner == reply.binder
        val unavailable = busy || failed
        if (message.sendingUid != applicationInfo.uid || unavailable || !sameOwner) {
            sendError(reply, "Whistle worker unavailable or busy")
        } else {
            owner = reply.binder
            busy = true
            val command = message.what
            val data = message.data
            executor.execute { execute(reply, command, data) }
        }
        return true
    }

    private fun execute(reply: Messenger, command: Int, data: Bundle) {
        segments.clear()
        try {
            val result = dispatchCommand(command, data)
            val completed = segments.toList()
            main.post {
                busy = false
                // Same sender/handler: all results precede their ACK. No next command races the busy flag.
                for (segment in completed) {
                    val response = Message.obtain(null, WhistleProtocol.SEGMENT).apply { this.data = segment }
                    if (!send(reply, response)) return@post
                }
                send(reply, Message.obtain(null, command).apply { this.data = result })
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // IPC boundary; every failure is returned, never a fallback.
            error: Exception,
        ) {
            failure(reply, error.message ?: "Whistle worker failed")
        } catch (_: LinkageError) {
            failure(reply, "Verified Whistle runtime could not load")
        } catch (_: OutOfMemoryError) {
            failure(reply, "Whistle worker out of memory", WhistleProtocol.ERROR_CODE_OUT_OF_MEMORY)
        }
    }

    private fun dispatchCommand(command: Int, data: Bundle): Bundle = when (command) {
        WhistleProtocol.OPEN -> open(data.getInt("rate"))
        WhistleProtocol.SEED_CACHE -> {
            seedCache(data)
            Bundle.EMPTY
        }
        WhistleProtocol.ACCEPT -> {
            requireNotNull(stream).accept(requireNotNull(data.getFloatArray("samples")))
            Bundle.EMPTY
        }
        WhistleProtocol.FINISH -> {
            val activeStream = requireNotNull(stream)
            val hashes = activeStream.finish()
            WhistleProtocol.finishBundle(
                hashes = hashes,
                cacheHits = activeStream.cacheHits,
                neuralTranscribes = activeStream.neuralTranscribes,
                workerCpuMs = Process.getElapsedCpuTime(),
            )
        }
        else -> error("Invalid Whistle worker command")
    }

    private fun open(rate: Int): Bundle {
        check(stream == null && assets == null)
        require(rate == SPEECH_RATE || rate == SOURCE_RATE)
        WhistleIsolation.verify(this)
        assets = WhistleAssets.verified(this)
        WhistleNative.bootstrap()
        val verified = requireNotNull(assets)
        WhistleNative.open(verified.needle, verified.ort, verified.model, verified.vad)
        stream = WhistleStream(rate, ::segment) { hash, count ->
            cachedSegments[hash]?.takeIf { it.first == count }?.second
        }
        return Bundle().apply {
            putInt("pid", Process.myPid())
            putInt("uid", Process.myUid())
        }
    }

    private fun seedCache(data: Bundle) {
        val count = data.getInt("count", 0)
        for (i in 0 until count) {
            if (cachedSegments.size >= WhistleSegmentCache.MAX_ENTRIES) break
            seedEntry(data, i)
        }
    }

    private fun seedEntry(data: Bundle, index: Int) {
        val hash = data.getString("hash_$index")
        val samples = data.getInt("samples_$index", 0)
        val json = data.getByteArray("json_$index")
        if (hash != null && json != null && WhistleSegmentCache.isValidEntry(hash, samples, json)) {
            cachedSegments[hash] = samples to json
        }
    }

    private fun segment(range: WhistleSpeechSegment, json: ByteArray, hash: String, cacheHit: Boolean) {
        segments.add(WhistleProtocol.segmentBundle(range, json, hash, cacheHit))
    }

    private fun failure(reply: Messenger, message: String, code: Int = WhistleProtocol.ERROR_CODE_GENERIC) {
        main.post {
            busy = false
            failed = true
            sendError(reply, message, code)
        }
    }

    private fun sendError(reply: Messenger, message: String, code: Int = WhistleProtocol.ERROR_CODE_GENERIC) {
        val response = Message.obtain(null, WhistleProtocol.ERROR).apply {
            data = WhistleProtocol.errorBundle(message, code)
        }
        send(reply, response)
    }

    private fun send(reply: Messenger, message: Message): Boolean = try {
        reply.send(message)
        true
    } catch (_: RemoteException) {
        failed = true
        false
    }

    companion object {
        private const val SPEECH_RATE = 16000
        private const val SOURCE_RATE = 48000
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        check(WhistleProtocol.ownsProcess(packageName, Application.getProcessName()))
        // Needle has no ownership-safe unload. Kill only this process, including an in-flight native call.
        Process.killProcess(Process.myPid())
        return false
    }
}
