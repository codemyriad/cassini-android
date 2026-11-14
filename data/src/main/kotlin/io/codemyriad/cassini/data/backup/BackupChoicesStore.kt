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

/**
 * Stored in noBackupFilesDir: neither the source phone's choices nor its card acknowledgement travels.
 *
 * Version 2 adds the D98 choices (cloud backup on by default, "Include saved voices in backups", per-channel size
 * warnings). A version 1 file was written only after a choice in Settings or a refused transfer, while the copy said
 * nothing is sent to a cloud account; it keeps its stored `cloud = false`, so an update never starts uploading for a
 * person who had already chosen. They can turn cloud backup on in Settings.
 */
class BackupChoicesStore(private val file: File, private val io: CoroutineDispatcher) {
    private val revision = MutableStateFlow(0L)
    val choices = revision.map { withContext(io) { readBlocking() } }.distinctUntilChanged()

    fun refresh() = revision.update { it + 1 }

    suspend fun setTransfer(enabled: Boolean) = edit {
        it.copy(transfer = enabled, noticeAcknowledged = true, quotaExceeded = false)
    }

    suspend fun setCloud(enabled: Boolean) = edit {
        it.copy(cloud = enabled, noticeAcknowledged = true, cloudQuotaExceeded = false)
    }

    suspend fun setVoices(enabled: Boolean) = edit { it.copy(voices = enabled, noticeAcknowledged = true) }

    /** "Keep on this phone only" (D98): no automatic copy at all. The switches stay independent afterwards. */
    suspend fun keepOnPhoneOnly() = edit {
        it.copy(
            cloud = false,
            transfer = false,
            noticeAcknowledged = true,
            quotaExceeded = false,
            cloudQuotaExceeded = false,
        )
    }

    /** "Keep backup on" or the card's close button: the choices stay as they are. */
    suspend fun acknowledgeNotice() = edit { it.copy(noticeAcknowledged = true) }

    /** The size refusal of the last [cloud] or transfer attempt. */
    suspend fun recordQuotaExceeded(value: Boolean, cloud: Boolean = false) = edit {
        if (cloud) it.copy(cloudQuotaExceeded = value) else it.copy(quotaExceeded = value)
    }

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
            parse(JSONObject(text))
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
            val current = readBlocking()
            val value = change(current)
            if (value == current) return@withLock
            Files.createDirectories(file.parentFile!!.toPath())
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                val json = JSONObject().put("version", VERSION)
                    .put("transfer", value.transfer).put("cloud", value.cloud).put("voices", value.voices)
                    .put("noticeAcknowledged", value.noticeAcknowledged).put("quotaExceeded", value.quotaExceeded)
                    .put("cloudQuotaExceeded", value.cloudQuotaExceeded)
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (error: IOException) {
                atomic.failWrite(output)
                throw error
            }
            revision.update { it + 1 }
        }
    }

    private companion object {
        const val VERSION = 2
        const val VERSION_1 = 1
        const val MAX_BYTES = 4096
        val lock = ReentrantLock()

        fun parse(json: JSONObject): BackupChoices = when (json.opt("version")) {
            VERSION -> BackupChoices(
                transfer = boolean(json, "transfer"),
                cloud = boolean(json, "cloud"),
                voices = boolean(json, "voices"),
                noticeAcknowledged = boolean(json, "noticeAcknowledged"),
                quotaExceeded = boolean(json, "quotaExceeded"),
                cloudQuotaExceeded = boolean(json, "cloudQuotaExceeded"),
            )
            VERSION_1 -> BackupChoices(
                transfer = boolean(json, "transfer"),
                cloud = boolean(json, "cloud"),
                voices = true,
                noticeAcknowledged = boolean(json, "noticeAcknowledged"),
                quotaExceeded = boolean(json, "quotaExceeded"),
            )
            else -> BackupChoices.UNREADABLE
        }

        fun boolean(json: JSONObject, name: String): Boolean =
            json.opt(name) as? Boolean ?: throw JSONException("Invalid backup choice")
    }
}
