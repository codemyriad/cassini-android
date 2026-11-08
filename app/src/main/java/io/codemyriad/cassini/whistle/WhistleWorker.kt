package io.codemyriad.cassini.whistle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.annotation.RequiresApi
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import io.codemyriad.cassini.engine.whistle.WhistleStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Blocking streaming client for the inference thread. One command/result ACK at a time bounds IPC and memory.
 * Close on every path: unbinding ends only the private process, including cancellation during native inference.
 * Main-thread calls are rejected; waits use Binder/queue events with a failure deadline, never sleeps.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WhistleWorker(
    context: Context,
    rate: Int,
    private val onSegment: (Bundle) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val thread = HandlerThread("WhistleReplies")
    private val connected = CompletableFuture<Messenger>()
    private val replies = ArrayBlockingQueue<Message>(REPLY_CAPACITY)
    private var remote: Messenger? = null
    private var bound = false

    @Volatile private var closed = false

    private val ownerThread = Thread.currentThread()
    private var inCommand = false
    private var ownsPermit = false
    private val death = CompletableFuture<Unit>()
    val pid: Int
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                binder.linkToDeath({ died() }, 0)
                connected.complete(Messenger(binder))
            } catch (_: RemoteException) {
                died()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = died()
        override fun onBindingDied(name: ComponentName) = died()
        override fun onNullBinding(name: ComponentName) = died()
    }
    private lateinit var receiver: Messenger

    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Whistle worker requires the inference thread" }
        check(WhistleDeviceCapability.current() == WhistleCapability.ELIGIBLE) { "Whistle unavailable on this device" }
        permit.acquire()
        ownsPermit = true
        var opened = false
        try {
            thread.start()
            val incoming = Handler(thread.looper) { receive(it) }
            receiver = Messenger(incoming)
            bound = app.bindService(Intent(app, WhistleService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Whistle worker could not bind" }
            remote = connected.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            pid = command(WhistleProtocol.OPEN, Bundle().apply { putInt("rate", rate) }).getInt("pid")
            check(pid > 0 && pid != android.os.Process.myPid()) { "Whistle must run in a private process" }
            opened = true
        } finally {
            if (!opened) close()
        }
    }

    fun accept(samples: FloatArray, count: Int = samples.size) {
        require(count in 0..samples.size)
        var at = 0
        while (at < count) {
            val end = minOf(at + WhistleStream.INPUT_BLOCK, count)
            command(WhistleProtocol.ACCEPT, Bundle().apply { putFloatArray("samples", samples.copyOfRange(at, end)) })
            at = end
        }
    }

    fun finish(): Bundle = try {
        command(WhistleProtocol.FINISH, Bundle.EMPTY)
    } finally {
        close()
    }

    private fun receive(message: Message): Boolean {
        if (!replies.offer(Message.obtain(message))) {
            replies.clear()
            replies.offer(errorReply("Whistle reply queue overflow"))
        }
        return true
    }

    private fun errorReply(error: String): Message {
        return Message.obtain(null, WhistleProtocol.ERROR).apply {
            data = Bundle().apply { putString("error", error) }
        }
    }

    private fun command(command: Int, data: Bundle): Bundle {
        check(Thread.currentThread() === ownerThread) { "Whistle commands require the owning inference thread" }
        check(!closed && !inCommand) { "Whistle worker is closed or already handling a command" }
        inCommand = true
        try {
            val request = Message.obtain(null, command).apply {
                this.data = data
                replyTo = receiver
            }
            remote?.send(request) ?: error("Whistle worker disconnected")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Whistle worker timed out" }
                val reply = replies.poll(remaining, TimeUnit.NANOSECONDS) ?: error("Whistle worker timed out")
                check(!closed) { "Whistle worker closed" }
                when (reply.what) {
                    WhistleProtocol.SEGMENT -> onSegment(reply.data)
                    WhistleProtocol.ERROR -> error(reply.data.getString("error") ?: "Whistle worker failed")
                    command -> return reply.data
                    else -> error("Unexpected Whistle worker reply")
                }
            }
        } finally {
            inCommand = false
        }
    }

    private fun died() {
        death.complete(Unit)
        connected.completeExceptionally(IllegalStateException("Whistle worker disconnected"))
        replies.clear()
        replies.offer(errorReply("Whistle worker disconnected"))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var interrupted = Thread.interrupted()
        try {
            if (bound) {
                app.unbindService(connection)
                bound = false
                if (remote != null) {
                    interrupted = cleanupWait { death.get(it, TimeUnit.NANOSECONDS) } || interrupted
                }
            }
        } finally {
            try {
                thread.quitSafely()
                if (thread.isAlive && thread !== Thread.currentThread()) {
                    interrupted = cleanupWait {
                        thread.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(it)))
                        check(!thread.isAlive) { "Whistle reply thread did not terminate" }
                    } || interrupted
                }
            } finally {
                remote = null
                if (ownsPermit) {
                    ownsPermit = false
                    permit.release()
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    /** Finish bounded cleanup even when cancellation interrupted the owner; restore the flag afterward. */
    private fun cleanupWait(event: (Long) -> Unit): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_SECONDS)
        var interrupted = false
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Whistle cleanup timed out" }
            try {
                event(remaining)
                return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    companion object {
        private val permit = Semaphore(1, true)
        private const val CLOSE_SECONDS = 10L
        private const val DEADLINE_SECONDS = 180L
        private const val REPLY_CAPACITY = 4
    }
}
