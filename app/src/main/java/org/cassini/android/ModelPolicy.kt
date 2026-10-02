package org.cassini.android

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.File

/** Conservative resource policy. Full precision is a quality preference, not a measured WER claim. */
internal object ModelPolicy {
    private const val GIB = 1024L * 1024 * 1024
    data class Resources(val totalRam: Long, val availableRam: Long, val lowMemory: Boolean,
                         val is64Bit: Boolean, val freeStorage: Long, val fp32DownloadBytes: Long)
    fun preferFp32(resources: Resources): Boolean = resources.is64Bit && !resources.lowMemory &&
        resources.totalRam >= 7 * GIB && resources.availableRam >= 3 * GIB &&
        resources.freeStorage >= resources.fp32DownloadBytes + 512L * 1024 * 1024
    fun resolve(context: Context, choice: String): Boolean = when (choice) {
        "fp32" -> true
        "int8" -> false
        else -> {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            val directory = File(context.filesDir, "parakeet-v3-fp32")
            val remaining = ModelStore.fp32Artifacts.filter { File(directory, it.name).length() != it.size }.sumOf { it.size }
            preferFp32(Resources(memory.totalMem, memory.availMem, memory.lowMemory, Process.is64Bit(), context.filesDir.usableSpace, remaining))
        }
    }
}

internal object ProcessingSpeed {
    /** Larger is faster: 60 s of audio processed in 30 s is 2x realtime. */
    fun realtime(audioMs: Long, elapsedMs: Long): Double? =
        if (audioMs > 0 && elapsedMs > 0) audioMs.toDouble() / elapsedMs else null

    /** Time left at the pace so far; null until a second of work has covered some audio, when a pace means something. */
    fun remainingMs(doneMs: Long, totalMs: Long, elapsedMs: Long): Long? =
        if (doneMs <= 0 || elapsedMs < 1000 || totalMs < doneMs) null else (totalMs - doneMs) * elapsedMs / doneMs
}
