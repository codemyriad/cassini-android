package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

class ArtifactFetcherTest {
    /** One HTTP answer. [sent] bytes of [body] are written, then the connection closes; [length] is what it announces. */
    private class Answer(
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
        val sent: Int = body.size,
        val length: Int = body.size,
    )

    /** A loopback server that records each request's headers and answers with [respond]. */
    private fun withServer(
        respond: (Map<String, String>) -> Answer,
        check: (String, File, List<Map<String, String>>) -> Unit
    ) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val directory = Files.createTempDirectory("cassini-model-test").toFile()
        val requests = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val responder = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                        requests += headers
                        val answer = respond(headers)
                        val extra = answer.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                        val status = "HTTP/1.1 ${answer.status} Response\r\nContent-Length: ${answer.length}\r\n"
                        val head = "$status${extra}Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(head.toByteArray())
                            write(answer.body, 0, answer.sent)
                            flush()
                        }
                    }
                } catch (error: java.net.SocketException) {
                    if (!server.isClosed) throw error
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            check("http://127.0.0.1:${server.localPort}/model", directory, requests)
        } finally {
            server.close()
            responder.join(1000)
            directory.deleteRecursively()
        }
    }

    private fun withServer(body: ByteArray, status: Int = 200, check: (String, File) -> Unit) =
        withServer({ Answer(status, body) }) { url, directory, _ -> check(url, directory) }

    private fun artifact(body: ByteArray) = Artifact(
        "model.onnx",
        body.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    )

    private fun failure(block: () -> Unit): Failure {
        try { block() } catch (error: UserFacingException) { return error.failure }
        fail("Expected a UserFacingException")
        error("unreachable")
    }

    private val weights = ByteArray(300_000) { (it * 7 % 253).toByte() }

    /** Answers a Range request with the requested suffix of [body], announcing [total] as the full length. */
    private fun ranged(
        body: ByteArray,
        total: Long = body.size.toLong()
    ): (Map<String, String>) -> Answer = { headers ->
        val range = headers["range"]
        if (range == null) {
            Answer(200, body)
        } else {
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            Answer(
                206,
                body.copyOfRange(from, body.size),
                mapOf("Content-Range" to "bytes $from-${body.size - 1}/$total")
            )
        }
    }

    @Test fun bytesThatDoNotMatchTheVoiceprintPinAreNotKept() {
        val bytes = "voiceprint weights".toByteArray()
        withServer(bytes) { url, directory ->
            val failure = failure { ArtifactFetcher.fetch(url, ModelCatalog.voiceprintModel, directory) { _, _ -> } }
            assertEquals(Failure.VERIFY, failure)
            assertFalse(File(directory, ModelCatalog.voiceprintModel.name).exists())
            assertFalse(File(directory, "${ModelCatalog.voiceprintModel.name}.part").exists())
        }
    }

    @Test fun verifiedBytesAreInstalledAndPartialFilesAreRemoved() {
        val bytes = "model weights".toByteArray()
        withServer(bytes) { url, directory ->
            ArtifactFetcher.fetch(url, artifact(bytes), directory) { _, _ -> }
            assertArrayEquals(bytes, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun aCorrectLengthWithWrongHashCannotReplaceAnExistingModel() {
        val bytes = "model weights".toByteArray()
        withServer("wrong weights".toByteArray()) { url, directory ->
            val original = "existing copy".toByteArray()
            File(directory, "model.onnx").writeBytes(original)
            assertEquals(Failure.VERIFY, failure { ArtifactFetcher.fetch(url, artifact(bytes), directory) { _, _ -> } })
            assertArrayEquals(original, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun unavailableModelsReportDownloadFailureAndKeepNoPartial() {
        val bytes = "missing model".toByteArray()
        withServer(bytes, 404) { url, directory ->
            assertEquals(
                Failure.DOWNLOAD,
                failure { ArtifactFetcher.fetch(url, artifact(bytes), directory) { _, _ -> } }
            )
            assertFalse(File(directory, "model.onnx").exists())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun anInterruptedDownloadCannotInstallItsResult() {
        val bytes = "model weights".toByteArray()
        withServer(bytes) { url, directory ->
            try {
                Thread.currentThread().interrupt()
                assertEquals(
                    Failure.CANCELLED,
                    failure { ArtifactFetcher.fetch(url, artifact(bytes), directory) { _, _ -> } }
                )
                assertFalse(File(directory, "model.onnx").exists())
            } finally { Thread.interrupted() }
        }
    }

    @Test fun aBrokenTransferKeepsItsPartAndTheNextAttemptResumesIt() {
        var attempts = 0
        val respond = ranged(weights)
        withServer({ headers ->
            attempts++
            // The first answer announces every byte but the connection drops after 100 000 of them.
            if (attempts == 1) Answer(200, weights, sent = 100_000) else respond(headers)
        }) { url, directory, requests ->
            try {
                ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
                fail("A truncated body must not install")
            } catch (error: UserFacingException) {
                assertEquals(Failure.DOWNLOAD, error.failure)
            } catch (_: IOException) {
                // Some HTTP stacks report the early close as an error instead of an early end.
            }
            assertFalse(File(directory, "model.onnx").exists())
            assertEquals(100_000L, File(directory, "model.onnx.part").length())
            val reported = mutableListOf<Long>()
            ArtifactFetcher.fetch(
                url,
                artifact(weights),
                directory
            ) { done, verifying -> if (!verifying) reported += done }
            assertEquals("bytes=100000-", requests.last()["range"])
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.part").exists())
            assertTrue("Progress continues from the resumed length", reported.all { it > 100_000 })
        }
    }

    @Test fun aPartialAnswerResumesFromThePartLength() {
        withServer(ranged(weights)) { url, directory, requests ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(123_456))
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertEquals(listOf("bytes=123456-"), requests.map { it["range"] })
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    /** The failure of one fetch of [weights] from [url] into [directory]. */
    private fun fetching(url: String, directory: File) =
        failure { ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> } }

    @Test fun aRangeThatEndsBeforeTheArtifactKeepsItsBytesAndTheNextAttemptGoesOn() {
        val cap = 100_000
        withServer({ headers ->
            // A server that caps every open-ended range at 100 000 bytes.
            val from = headers.getValue("range").removePrefix("bytes=").removeSuffix("-").toInt()
            val to = minOf(from + cap, weights.size)
            Answer(
                206,
                weights.copyOfRange(from, to),
                mapOf("Content-Range" to "bytes $from-${to - 1}/${weights.size}")
            )
        }) { url, directory, requests ->
            val part = File(directory, "model.onnx.part")
            part.writeBytes(weights.copyOf(1000))
            assertEquals(Failure.DOWNLOAD, fetching(url, directory))
            assertEquals(101_000L, part.length())
            assertEquals(weights.size - 101_000L, ArtifactFetcher.bytesToFetch(artifact(weights), directory))
            assertEquals(Failure.DOWNLOAD, fetching(url, directory))
            assertEquals(201_000L, part.length())
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertEquals(listOf("bytes=1000-", "bytes=101000-", "bytes=201000-"), requests.map { it["range"] })
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
            assertFalse(part.exists())
        }
    }

    @Test fun aRangeThatEndsPastTheArtifactDeletesThePart() {
        withServer({
            Answer(
                206,
                weights.copyOfRange(1000, weights.size),
                mapOf("Content-Range" to "bytes 1000-${weights.size}/${weights.size}")
            )
        }) { url, directory, _ ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(Failure.VERIFY, fetching(url, directory))
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun onlyAPartThatCanStillBeAPrefixReducesTheBytesToFetch() {
        val directory = Files.createTempDirectory("cassini-model-test").toFile()
        try {
            val target = artifact(weights)
            assertEquals(weights.size.toLong(), ArtifactFetcher.bytesToFetch(target, directory))
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(weights.size - 1000L, ArtifactFetcher.bytesToFetch(target, directory))
            File(directory, "model.onnx.part").writeBytes(ByteArray(weights.size + 1))
            assertEquals(weights.size.toLong(), ArtifactFetcher.bytesToFetch(target, directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aFullAnswerToARangeRequestRestartsFromZero() {
        withServer({ Answer(200, weights) }) { url, directory, requests ->
            // Not a prefix of the artifact: a restart must not keep any of it.
            File(directory, "model.onnx.part").writeBytes(ByteArray(50_000) { 1 })
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertEquals("bytes=50000-", requests.single()["range"])
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    @Test fun aWrongContentRangeTotalDeletesThePart() {
        withServer(ranged(weights, total = weights.size + 1L)) { url, directory, _ ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(
                Failure.VERIFY,
                failure { ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> } }
            )
            assertFalse(File(directory, "model.onnx.part").exists())
            assertFalse(File(directory, "model.onnx").exists())
        }
    }

    @Test fun aContentRangeForAnotherOffsetDeletesThePart() {
        withServer({
            Answer(
                206,
                weights.copyOfRange(10, weights.size),
                mapOf("Content-Range" to "bytes 10-${weights.size - 1}/${weights.size}")
            )
        }) {
                url, directory, _ ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(
                Failure.VERIFY,
                failure { ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> } }
            )
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun aHashMismatchAfterResumingDeletesThePart() {
        withServer(ranged(weights)) { url, directory, _ ->
            // The right length, the wrong bytes: only the hash can tell.
            File(directory, "model.onnx.part").writeBytes(ByteArray(1000) { 9 })
            assertEquals(
                Failure.VERIFY,
                failure { ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> } }
            )
            assertFalse(File(directory, "model.onnx.part").exists())
            assertFalse(File(directory, "model.onnx").exists())
            // The next attempt starts over and succeeds.
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    @Test fun aPartLongerThanTheArtifactIsDiscarded() {
        withServer(ranged(weights)) { url, directory, requests ->
            File(directory, "model.onnx.part").writeBytes(ByteArray(weights.size + 1))
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertEquals(null, requests.single()["range"])
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    @Test fun anUnsatisfiableRangeDeletesThePart() {
        withServer({ Answer(416, ByteArray(0)) }) { url, directory, _ ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(
                Failure.DOWNLOAD,
                failure { ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> } }
            )
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun aServerErrorKeepsThePartForTheNextAttempt() {
        withServer({ Answer(503, ByteArray(0)) }) { url, directory, _ ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(
                Failure.DOWNLOAD,
                failure { ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> } }
            )
            assertEquals(1000L, File(directory, "model.onnx.part").length())
        }
    }

    @Test fun aVerifiedFileIsNeverReplacedOrDownloadedAgain() {
        withServer({ Answer(200, ByteArray(weights.size)) }) { url, directory, requests ->
            File(directory, "model.onnx").writeBytes(weights)
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertEquals(0, requests.size)
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    @Test fun aCompletePartIsVerifiedWithoutARequest() {
        withServer({ Answer(500, ByteArray(0)) }) { url, directory, requests ->
            File(directory, "model.onnx.part").writeBytes(weights)
            ArtifactFetcher.fetch(url, artifact(weights), directory) { _, _ -> }
            assertEquals(0, requests.size)
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    /** Redirects within the loopback test server: plain HTTP to 127.0.0.1 or localhost. */
    private val loopback = RedirectPolicy(listOf("127.0.0.1", "localhost"), httpsOnly = false)

    private fun redirect(location: String, status: Int = 302) =
        Answer(status, ByteArray(0), mapOf("Location" to location))

    /** The failure of fetching [weights] from [url] under [policy]. */
    private fun fetchFailure(url: String, directory: File, policy: RedirectPolicy = RedirectPolicy.DOWNLOADS) =
        failure { ArtifactFetcher.fetch(url, artifact(weights), directory, policy) { _, _ -> } }

    private fun port(url: String) = java.net.URI(url).port

    @Test fun anAllowedRedirectChainIsFollowedAndKeepsTheRange() {
        var port = 0
        var attempts = 0
        val respond = ranged(weights)
        withServer({ headers ->
            attempts++
            when (attempts) {
                1 -> redirect("http://localhost:$port/cdn")
                2 -> redirect("/resolve-cache", 307)
                3 -> redirect("http://127.0.0.1:$port/final", 301)
                else -> respond(headers)
            }
        }) { url, directory, requests ->
            port = port(url)
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            ArtifactFetcher.fetch(url, artifact(weights), directory, loopback) { _, _ -> }
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
            assertEquals(4, requests.size)
            assertEquals(List(4) { "bytes=1000-" }, requests.map { it["range"] })
            val hosts = listOf("127.0.0.1:$port", "localhost:$port", "localhost:$port", "127.0.0.1:$port")
            assertEquals(hosts, requests.map { it["host"] })
        }
    }

    @Test fun aRedirectToAForeignHostFailsWithoutContactingItAndKeepsThePart() {
        withServer({ redirect("https://example.com/model.onnx") }) { url, directory, requests ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(1000))
            assertEquals(Failure.DOWNLOAD, fetchFailure(url, directory))
            assertEquals(1, requests.size)
            assertEquals(1000L, File(directory, "model.onnx.part").length())
            assertFalse(File(directory, "model.onnx").exists())
        }
    }

    @Test fun aRedirectToAHostOffTheListIsNeverFollowed() {
        // HttpURLConnection would follow this one by itself: same scheme, a host that answers.
        val onlyLoopbackAddress = RedirectPolicy(listOf("127.0.0.1"), httpsOnly = false)
        var port = 0
        var attempts = 0
        withServer({
            if (++attempts == 1) redirect("http://localhost:$port/elsewhere") else Answer(200, weights)
        }) { url, directory, requests ->
            port = port(url)
            assertEquals(Failure.DOWNLOAD, fetchFailure(url, directory, onlyLoopbackAddress))
            assertEquals(1, requests.size)
            assertFalse(File(directory, "model.onnx").exists())
        }
    }

    @Test fun aRedirectToPlainHttpIsRefusedEvenForAnAllowedHost() {
        val httpsOnly = RedirectPolicy(listOf("127.0.0.1", "huggingface.co"))
        var port = 0
        withServer({ redirect("http://127.0.0.1:$port/again") }) { url, directory, requests ->
            port = port(url)
            assertEquals(Failure.DOWNLOAD, fetchFailure(url, directory, httpsOnly))
            assertEquals(1, requests.size)
        }
        withServer({ redirect("http://huggingface.co/repo/model") }) { url, directory, requests ->
            assertEquals(Failure.DOWNLOAD, fetchFailure(url, directory))
            assertEquals(1, requests.size)
        }
    }

    @Test fun moreThanFiveRedirectsFail() {
        withServer({ redirect("/again") }) { url, directory, requests ->
            assertEquals(Failure.DOWNLOAD, fetchFailure(url, directory, loopback))
            assertEquals(1 + RedirectPolicy.MAX_REDIRECTS, requests.size)
            assertFalse(File(directory, "model.onnx").exists())
        }
    }

    @Test fun fiveRedirectsAreStillFollowed() {
        var attempts = 0
        withServer({
            if (++attempts <= RedirectPolicy.MAX_REDIRECTS) redirect("/hop$attempts") else Answer(200, weights)
        }) { url, directory, requests ->
            ArtifactFetcher.fetch(url, artifact(weights), directory, loopback) { _, _ -> }
            assertEquals(6, requests.size)
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    /** Asserts that the verifying updates come after every download update, rise from 0 and end at [total]. */
    private fun assertChecking(events: List<Pair<Long, Boolean>>, total: Long) {
        val firstCheck = events.indexOfFirst { it.second }
        assertTrue(events.drop(firstCheck).all { it.second })
        val checked = events.drop(firstCheck).map { it.first }
        assertEquals(0L, checked.first())
        assertEquals(total, checked.last())
        assertEquals(checked.sorted(), checked)
        assertTrue("$checked", checked.any { it in 1 until total })
    }

    @Test fun verifyingReportsTheBytesCheckedSoFar() {
        withServer(weights) { url, directory ->
            val events = mutableListOf<Pair<Long, Boolean>>()
            ArtifactFetcher.fetch(url, artifact(weights), directory) { bytes, verifying ->
                events += bytes to verifying
            }
            assertFalse(events.first().second)
            assertChecking(events, weights.size.toLong())
        }
    }

    @Test fun theDigestReportsEachStepAndTheEnd() {
        val file = Files.createTempFile("cassini-digest", ".bin").toFile()
        try {
            file.writeBytes(weights)
            val seen = mutableListOf<Long>()
            val hash = ArtifactFetcher.digest(file) { seen += it }
            assertEquals(artifact(weights).sha256, hash)
            assertEquals(128L * 1024, seen.first())
            assertEquals(weights.size.toLong(), seen.last())
        } finally {
            file.delete()
        }
    }

    @Test fun aCompressedDownloadChecksOnceFromZeroToItsSize() {
        val compressed = com.github.luben.zstd.Zstd.compress(weights)
        val download = artifact(compressed).copy(name = "model.onnx.zst")
        withServer(compressed) { url, directory ->
            val events = mutableListOf<Pair<Long, Boolean>>()
            ArtifactFetcher.fetchZstd(url, download, artifact(weights), directory) { bytes, verifying ->
                events += bytes to verifying
            }
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.zst").exists())
            assertChecking(events, compressed.size.toLong())
        }
    }
}
