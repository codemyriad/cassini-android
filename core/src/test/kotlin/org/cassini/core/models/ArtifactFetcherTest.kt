package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
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

    @Test fun theVoiceprintModelIsFetchedAsIsAndVerified() {
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
}
