package io.codemyriad.cassini.core.recorder

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The receive side of an XREC link, with a timeout on each read. Tests replace it with a scripted source. */
fun interface XrecInput {
    /**
     * Reads at most [length] bytes into [buffer] from [offset], waiting at most [timeoutMillis] for the first one.
     * Returns the count read (> 0), 0 when nothing arrived within [timeoutMillis], or -1 at the end of the stream.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Long): Int
}

/**
 * An [XrecInput] over a blocking [InputStream], such as the stream of an RFCOMM socket, which has no read timeout. A
 * daemon thread copies the stream into a bounded buffer; [read] waits on that buffer. [close] closes the stream, which
 * ends the thread.
 */
class StreamXrecInput(
    private val stream: InputStream,
    capacity: Int = DEFAULT_CAPACITY,
    threadName: String = "xrec-input",
) : XrecInput, Closeable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ring = ByteArray(capacity)
    private var head = 0
    private var count = 0
    private var closed = false
    private var ended = false
    private var failure: IOException? = null
    private val pump = Thread(::pumpLoop, threadName).apply { isDaemon = true }

    init {
        pump.start()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Long): Int = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        while (canWait() && remaining > 0) {
            remaining = changed.awaitNanos(remaining)
        }
        when {
            closed -> -1
            count > 0 -> take(buffer, offset, length)
            ended -> failure?.let { throw IOException("The XREC link failed", it) } ?: -1
            else -> 0
        }
    }

    private fun canWait(): Boolean = count == 0 && !ended && !closed

    override fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        stream.close()
    }

    private fun take(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, count)
        repeat(n) { i -> buffer[offset + i] = ring[(head + i) % ring.size] }
        head = (head + n) % ring.size
        count -= n
        changed.signalAll()
        return n
    }

    private fun pumpLoop() {
        val chunk = ByteArray(CHUNK)
        try {
            while (!lock.withLock { closed }) {
                val n = stream.read(chunk)
                if (n < 0 || !put(chunk, n)) break
            }
        } catch (e: IOException) {
            lock.withLock { failure = e }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            lock.withLock {
                ended = true
                changed.signalAll()
            }
        }
    }

    private fun put(chunk: ByteArray, n: Int): Boolean = lock.withLock {
        var written = 0
        while (written < n && !closed) {
            while (count == ring.size && !closed) {
                changed.signalAll()
                changed.await()
            }
            if (!closed) {
                val tail = (head + count) % ring.size
                ring[tail] = chunk[written++]
                count++
            }
        }
        changed.signalAll()
        !closed
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64 * 1024
        const val CHUNK = 1024
    }
}
