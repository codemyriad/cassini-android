package io.codemyriad.cassini.data.backup

import android.util.AtomicFile
import io.codemyriad.cassini.core.backup.BackupChoices
import io.codemyriad.cassini.data.Threads
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Stored in noBackupFilesDir: neither a source cloud opt-in nor its notice acknowledgement travels. */
class BackupChoicesStore(private val file: File, private val io: CoroutineDispatcher) {
    private val revision = MutableStateFlow(0L)
    val choices = revision.map { withContext(io) { readBlocking() } }.distinctUntilChanged()

    fun refresh() = revision.update { it + 1 }

    suspend fun setTransfer(enabled: Boolean) = edit { it.copy(transfer = enabled, noticeAcknowledged = true) }

    suspend fun setCloud(enabled: Boolean) = edit { it.copy(cloud = enabled, quotaExceeded = false) }

    suspend fun acknowledgeNotice() = edit { it.copy(noticeAcknowledged = true) }

    suspend fun recordQuotaExceeded(value: Boolean) = edit { it.copy(quotaExceeded = value) }

    fun readBlocking(): BackupChoices = lock.withLock {
        Threads.assertOffMain("BackupChoicesStore.readBlocking")
        try {
            val text = AtomicFile(file).openRead().use { input ->
                val bytes = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > MAX_BYTES) throw IOException("Oversized backup choices")
                String(bytes, 0, size, Charsets.UTF_8)
            }
            val json = JSONObject(text)
            if (json.opt("version") != VERSION) return@withLock BackupChoices.UNREADABLE
            BackupChoices(
                transfer = boolean(json, "transfer"),
                cloud = boolean(json, "cloud"),
                noticeAcknowledged = boolean(json, "noticeAcknowledged"),
                quotaExceeded = boolean(json, "quotaExceeded"),
            )
        } catch (_: FileNotFoundException) {
            val absent = Files.isDirectory(file.parentFile!!.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.notExists(File(file.path + ".bak").toPath(), LinkOption.NOFOLLOW_LINKS)
            if (absent) BackupChoices() else BackupChoices.UNREADABLE
        } catch (_: IOException) {
            BackupChoices.UNREADABLE
        } catch (_: JSONException) {
            BackupChoices.UNREADABLE
        }
    }

    private suspend fun edit(change: (BackupChoices) -> BackupChoices) = withContext(io) {
        lock.withLock {
            val value = change(readBlocking())
            Files.createDirectories(file.parentFile!!.toPath())
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                val json = JSONObject().put(
                    "version",
                    VERSION
                ).put("transfer", value.transfer).put("cloud", value.cloud)
                    .put("noticeAcknowledged", value.noticeAcknowledged).put("quotaExceeded", value.quotaExceeded)
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (error: IOException) {
                atomic.failWrite(output)
                throw error
            }
            revision.update { it + 1 }
        }
    }

    private fun boolean(json: JSONObject, name: String): Boolean =
        json.opt(name) as? Boolean ?: throw JSONException("Invalid backup choice")

    private companion object {
        const val VERSION = 1
        const val MAX_BYTES = 4096
        val lock = ReentrantLock()
    }
}
