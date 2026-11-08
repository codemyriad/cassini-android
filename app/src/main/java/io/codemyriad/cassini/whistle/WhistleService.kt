package io.codemyriad.cassini.whistle

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechSegment
import io.codemyriad.cassini.engine.whistle.WhistleAssets
import io.codemyriad.cassini.engine.whistle.WhistleNative
import io.codemyriad.cassini.engine.whistle.WhistleStream
import java.util.concurrent.Executors

/** Private process; no graph, model-store, network client or system NN runtime. Reclaimed on client unbind. */
@RequiresApi(Build.VERSION_CODES.Q)
class WhistleService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var assets: WhistleAssets? = null // Retain both read-only mappings until process exit.
    private var stream: WhistleStream? = null
    private var owner: IBinder? = null
    private var busy = false
    private var failed = false
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
            val result = when (command) {
                WhistleProtocol.OPEN -> open(data.getInt("rate"))
                WhistleProtocol.ACCEPT -> {
                    requireNotNull(stream).accept(requireNotNull(data.getFloatArray("samples")))
                    Bundle.EMPTY
                }
                WhistleProtocol.FINISH -> {
                    val hashes = requireNotNull(stream).finish()
                    Bundle().apply {
                        putString("rawSha256", hashes.rawSha256)
                        putString("vadSha256", hashes.vadSha256)
                        putInt("vadFrames", hashes.frames)
                        putInt("voicedFrames", hashes.voiced)
                        putFloat("maxProbability", hashes.maximum)
                    }
                }
                else -> error("Invalid Whistle worker command")
            }
            val completed = segments.toList()
            main.post {
                busy = false
                // Same sender/handler: all results precede their ACK. No next command races the busy flag.
                for (segment in completed) {
                    val response = Message.obtain(null, WhistleProtocol.SEGMENT).apply { this.data = segment }
                    reply.send(response)
                }
                reply.send(Message.obtain(null, command).apply { this.data = result })
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // IPC boundary; every failure is returned, never a fallback.
            error: Exception,
        ) {
            failure(reply, error.message ?: "Whistle worker failed")
        } catch (_: LinkageError) {
            failure(reply, "Verified Whistle runtime could not load")
        } catch (_: OutOfMemoryError) {
            failure(reply, "Whistle worker out of memory")
        }
    }

    private fun open(rate: Int): Bundle {
        check(stream == null && assets == null)
        require(rate == SPEECH_RATE || rate == SOURCE_RATE)
        verifyIsolation()
        assets = WhistleAssets.verified(this)
        WhistleNative.bootstrap()
        val verified = requireNotNull(assets)
        WhistleNative.open(verified.needle, verified.ort, verified.model, verified.vad)
        stream = WhistleStream(rate, ::segment)
        return Bundle().apply {
            putInt("pid", Process.myPid())
            putInt("uid", Process.myUid())
        }
    }

    /** No host/contact is opened. Exercise the kernel/SELinux boundary before loading either neural runtime. */
    private fun verifyIsolation() {
        check(Process.myUid() != applicationInfo.uid) { "Whistle requires an isolated UID" }
        check(checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        for (family in listOf(OsConstants.AF_INET, OsConstants.AF_INET6)) {
            for (type in listOf(OsConstants.SOCK_STREAM, OsConstants.SOCK_DGRAM)) {
                deniedSocket(family, type)
            }
        }
    }

    private fun deniedSocket(family: Int, type: Int) {
        try {
            val socket = Os.socket(family, type, 0)
            Os.close(socket)
            error("Whistle isolated process unexpectedly permits Internet sockets")
        } catch (error: ErrnoException) {
            check(error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                "Whistle socket check failed without proving network denial"
            }
        }
    }

    private fun segment(range: WhistleSpeechSegment, json: ByteArray, hash: String) {
        val message = Bundle().apply {
            putLong("start", range.startSample)
            putLong("end", range.endSample)
            putString("reason", range.reason)
            putString("pcmSha256", hash)
            putByteArray("json", json)
        }
        segments.add(message)
    }

    private fun failure(reply: Messenger, message: String) {
        main.post {
            busy = false
            failed = true
            sendError(reply, message)
        }
    }

    private fun sendError(reply: Messenger, message: String) {
        val response = Message.obtain(null, WhistleProtocol.ERROR).apply {
            data = Bundle().apply { putString("error", message) }
        }
        reply.send(response)
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
