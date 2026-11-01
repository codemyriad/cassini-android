package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.requireUser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads pinned artifacts. A file is installed only after its size and SHA-256 match, and a verified file is never
 * replaced. An interrupted download keeps its `.part` file (through failures and process death) and resumes it with
 * an HTTP Range request; a `.part` that cannot be the artifact is deleted. A transfer that ends early keeps its
 * prefix and fails with [Failure.DOWNLOAD]. Free-space checks count only the bytes still to fetch ([bytesToFetch]),
 * because a kept `.part` already holds the rest.
 *
 * Redirects are followed by hand, only as [RedirectPolicy] allows: HTTPS to [ModelCatalog.DOWNLOAD_HOSTS], at
 * most five hops. Any other redirect fails the file with [Failure.DOWNLOAD] and keeps its `.part`.
 *
 * Every request asks for `Accept-Encoding: identity`: the bytes counted, resumed and hashed are the file's own, never
 * a transfer encoding of them. A resumed request also sends `If-Range` with the entity tag of the pinned file, which
 * the model host gives as its quoted SHA-256: a server holding other bytes answers the whole file (`200`), and the
 * download restarts from zero.
 */
@Suppress("TooManyFunctions") // one HTTP client for the model host: fetch, resume, decompress and read, and helpers
object ArtifactFetcher {
    private const val TIMEOUT_MS = 30_000

    /** Space left free on top of what a download writes (32 MiB). */
    const val SLACK_BYTES = 32 * 1024 * 1024L
    private const val BUFFER_BYTES = 128 * 1024
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+)""")
    private const val HTTP_TEMPORARY_REDIRECT = 307
    private const val HTTP_PERMANENT_REDIRECT = 308
    private val redirectCodes = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        HTTP_TEMPORARY_REDIRECT,
        HTTP_PERMANENT_REDIRECT,
    )

    /**
     * Hex SHA-256 of [file], checking for interruption between blocks. [onBytes] receives the bytes hashed so far:
     * after the first block, then at most every half second, and always once at the end with the file's length (a
     * block that reaches the end is reported only there, after the last interruption check).
     */
    fun digest(file: File, onBytes: (Long) -> Unit = {}): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastUpdate = 0L
        val length = file.length()
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(BUFFER_BYTES)
            while (true) {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
                done += count
                if (done < length && System.currentTimeMillis() - lastUpdate > PROGRESS_INTERVAL_MS) {
                    onBytes(done)
                    lastUpdate = System.currentTimeMillis()
                }
            }
        }
        onBytes(done)
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The bytes of [artifact] still to download into [directory]: its size less a `.part` that can still be its
     * prefix. A longer `.part` is deleted by [fetch], so it saves nothing.
     */
    fun bytesToFetch(artifact: Artifact, directory: File): Long {
        val kept = File(directory, "${artifact.name}.part").length()
        return if (kept > artifact.size) artifact.size else artifact.size - kept
    }

    /**
     * Places [artifact] in [directory] unless a verified copy is already there. The download goes to a
     * `.part` file that replaces nothing until its size and SHA-256 match. [progress] receives the bytes
     * done so far and whether they are being verified rather than downloaded: while downloading, the bytes in the
     * `.part`; while verifying, the bytes hashed so far (from 0 up to the artifact's size, which always comes last).
     * [redirects] decides which redirects are followed.
     *
     * A `.part` left by an earlier attempt is resumed with `Range: bytes=<length>-` and `If-Range: "<sha256>"`. A
     * `206` answer is accepted
     * only when its `Content-Range` starts at that length and its total is the pinned size; a `200` answer restarts
     * from zero. A `206` that ends before the artifact's last byte (some servers cap open-ended ranges) keeps what it
     * brought and fails with [Failure.DOWNLOAD], so the next attempt resumes further on. On a size or SHA-256
     * mismatch the `.part` is deleted. The 30 s read timeout stays: interrupting the thread does not unblock
     * `HttpURLConnection`.
     */
    fun fetch(
        url: String,
        artifact: Artifact,
        directory: File,
        redirects: RedirectPolicy = RedirectPolicy.DOWNLOADS,
        progress: (Long, Boolean) -> Unit,
    ) {
        val destination = File(directory, artifact.name)
        val checking: (Long) -> Unit = { progress(it, true) }
        if (destination.length() == artifact.size && digest(destination, checking) == artifact.sha256) {
            progress(artifact.size, true)
            return
        }
        val partial = File(directory, "${artifact.name}.part")
        // A longer file cannot be a prefix of the artifact.
        if (partial.length() > artifact.size) partial.delete()
        val offset = partial.length()
        requireUser(directory.usableSpace > artifact.size - offset + SLACK_BYTES, Failure.SPACE)
        if (offset < artifact.size) download(url, artifact, partial, redirects, progress)
        progress(0, true)
        if (partial.length() != artifact.size || digest(partial, checking) != artifact.sha256) {
            partial.delete()
            throw UserFacingException(Failure.VERIFY)
        }
        requireUser(partial.renameTo(destination), Failure.INSTALL)
        progress(artifact.size, true)
    }

    /** Downloads the rest of [artifact] into [partial], from its current length on. */
    private fun download(
        url: String,
        artifact: Artifact,
        partial: File,
        redirects: RedirectPolicy,
        progress: (Long, Boolean) -> Unit,
    ) {
        val offset = partial.length()
        val connection = open(url, offset, "\"${artifact.sha256}\"", redirects)
        try {
            // Where a 206 answer's range ends (exclusive); null for a whole 200 answer.
            val rangeEnd = resumes(connection, artifact, partial, offset)
            val first = if (rangeEnd != null) offset else 0L
            // Where the answer says it ends; -1 when it does not say.
            val announced = connection.contentLengthLong.let { if (it < 0) -1 else first + it }
            val received = connection.inputStream.buffered().use { input ->
                FileOutputStream(partial, first > 0).buffered().use { output ->
                    copy(input, output, first, artifact.size) { progress(it, false) }
                }
            }
            // A connection that closed before the announced end leaves a prefix, and so does a range that stops
            // short of the artifact's end: keep it for the next attempt.
            val expected = if (rangeEnd != null) artifact.size else announced
            if (received < expected) {
                throw UserFacingException(Failure.DOWNLOAD, "Transfer ended at $received of $expected bytes")
            }
        } catch (error: UserFacingException) {
            // More bytes than the artifact has, or another file: the partial download cannot be finished.
            if (error.failure == Failure.VERIFY) partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads the whole answer to a GET of [url], at most [limit] bytes, following the redirects [redirects] allows
     * (the model host's manifest). An answer other than `200` or shorter than it announced fails with
     * [Failure.DOWNLOAD]; a longer one with [Failure.VERIFY].
     */
    fun read(url: String, limit: Long, redirects: RedirectPolicy = RedirectPolicy.DOWNLOADS): ByteArray {
        val connection = open(url, 0, null, redirects)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw UserFacingException(Failure.DOWNLOAD, "HTTP $status")
            val announced = connection.contentLengthLong
            requireUser(announced <= limit, Failure.VERIFY)
            val body = ByteArrayOutputStream()
            val received = connection.inputStream.buffered().use { copy(it, body, 0, limit) {} }
            if (received < announced) {
                throw UserFacingException(Failure.DOWNLOAD, "Transfer ended at $received of $announced bytes")
            }
            return body.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Connects to [url] (asking for the bytes from [offset] on, when it is not 0, if the entity tag is still
     * [ifRange]) and follows the redirects [policy] allows, sending the same request each time. Returns the
     * connection of the first answer that is not a redirect, with its status read.
     */
    private fun open(url: String, offset: Long, ifRange: String?, policy: RedirectPolicy): HttpURLConnection {
        var target = URL(url)
        var hop = 0
        while (true) {
            val connection = target.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                ifRange?.let { connection.setRequestProperty("If-Range", it) }
            }
            val status = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            }
            if (status !in redirectCodes) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            hop += 1
            target = policy.next(target, location, hop)
        }
    }

    /**
     * Whether [connection]'s answer continues the [offset] bytes of [partial]: for a `206` whose range starts there,
     * ends inside the artifact and has the pinned size as its total, the offset just past the range's last byte;
     * null for a whole `200` answer. Any other answer fails; an unsatisfiable range also deletes [partial], because
     * the server's file is shorter than it.
     */
    private fun resumes(connection: HttpURLConnection, artifact: Artifact, partial: File, offset: Long): Long? {
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_OK) return null
        if (status != HttpURLConnection.HTTP_PARTIAL || offset == 0L) {
            if (status == HTTP_RANGE_NOT_SATISFIABLE) partial.delete()
            throw UserFacingException(Failure.DOWNLOAD, "HTTP $status")
        }
        val header = connection.getHeaderField("Content-Range").orEmpty().trim()
        val range = contentRange.matchEntire(header)?.destructured?.toList()?.map { it.toLongOrNull() }
        val (start, end, total) = range ?: throw UserFacingException(Failure.VERIFY, "Content-Range $header")
        val ends = end != null && end >= offset && end < artifact.size
        requireUser(start == offset && total == artifact.size && ends, Failure.VERIFY)
        return requireNotNull(end) + 1
    }

    /**
     * Copies [input] to [output] and returns the byte count, starting from [start]. A count above [limit] fails
     * with [Failure.VERIFY]. [progress] receives the count at most every half second.
     */
    private fun copy(
        input: InputStream,
        output: OutputStream,
        start: Long,
        limit: Long,
        progress: (Long) -> Unit,
    ): Long {
        val bytes = ByteArray(BUFFER_BYTES)
        var count = start
        var lastUpdate = 0L
        while (true) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val read = input.read(bytes)
            if (read < 0) return count
            count += read
            requireUser(count <= limit, Failure.VERIFY)
            output.write(bytes, 0, read)
            if (System.currentTimeMillis() - lastUpdate > PROGRESS_INTERVAL_MS) {
                progress(count)
                lastUpdate = System.currentTimeMillis()
            }
        }
    }

    /**
     * Like [fetch] for a Zstandard-compressed [download] of [artifact] (each model file on the model host is its own
     * seekable Zstandard object). The compressed file is fetched and verified (size and SHA-256), then decompressed
     * to a `.part` file that replaces nothing until the decompressed size and SHA-256 match. The verified compressed
     * file is not kept; its own `.part` is resumed like any other.
     *
     * [progress] counts in bytes of [download], as for [fetch]. While verifying, the count covers the whole check:
     * hashing the compressed file, decompressing it and hashing the result, each share in proportion to the bytes
     * it reads or writes, so "Checking files" rises once from 0 to the download's size.
     */
    @Suppress("LongParameterList") // the two pins, where they go and how: no smaller shape reads better
    fun fetchZstd(
        url: String,
        download: Artifact,
        artifact: Artifact,
        directory: File,
        redirects: RedirectPolicy = RedirectPolicy.DOWNLOADS,
        progress: (Long, Boolean) -> Unit,
    ) {
        val destination = File(directory, artifact.name)
        val existing = scaled(download.size, artifact.size)
        val installed = destination.length() == artifact.size &&
            digest(destination) { progress(existing(it), true) } == artifact.sha256
        if (installed) {
            progress(download.size, true)
            return
        }
        // A kept `.part` of the compressed file already holds its share; the decompressed copy needs all of its own.
        val needed = bytesToFetch(download, directory) + artifact.size + SLACK_BYTES
        requireUser(directory.usableSpace > needed, Failure.SPACE)
        val compressed = File(directory, download.name)
        // The check reads the download once and writes and reads the decompressed file once each.
        val check = scaled(download.size, download.size + 2 * artifact.size)
        try {
            fetch(url, download, directory, redirects) { bytes, verifying ->
                // fetch ends with the download's size once it is installed; the check is not over yet.
                if (verifying) progress(check(minOf(bytes, download.size)), true) else progress(bytes, false)
            }
            decompress(compressed, artifact, directory) { progress(check(download.size + it), true) }
        } finally {
            compressed.delete()
        }
        progress(download.size, true)
    }

    /** Maps a count out of [of] to the same share of [to]. */
    private fun scaled(to: Long, of: Long): (Long) -> Long =
        { done -> if (of <= 0) to else (to.toDouble() * done.coerceIn(0, of) / of).toLong() }

    /**
     * Decompresses a verified Zstandard [source] into [directory] as [artifact], or fails without a file.
     * [progress] receives the bytes written, then the artifact's size plus the bytes hashed (up to twice its size).
     */
    fun decompress(source: File, artifact: Artifact, directory: File, progress: (Long) -> Unit = {}) {
        val destination = File(directory, artifact.name)
        val partial = File(directory, "${artifact.name}.part")
        try {
            // Seekable Zstandard ends with a skippable seek-table frame, which the decoder skips.
            com.github.luben.zstd.ZstdInputStream(source.inputStream().buffered()).use { input ->
                partial.outputStream().buffered().use { output -> copy(input, output, 0, artifact.size, progress) }
            }
            val matches = partial.length() == artifact.size &&
                digest(partial) { progress(artifact.size + it) } == artifact.sha256
            requireUser(matches, Failure.VERIFY)
            requireUser(partial.renameTo(destination), Failure.INSTALL)
        } catch (error: IOException) {
            throw UserFacingException(Failure.VERIFY, error.message ?: "Zstandard").apply { initCause(error) }
        } finally {
            partial.delete()
        }
    }
}
