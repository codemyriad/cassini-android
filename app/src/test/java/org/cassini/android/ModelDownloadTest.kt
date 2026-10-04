package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest

class ModelDownloadTest {
    private fun withServer(body: ByteArray, status: Int = 200, check: (String, File) -> Unit) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val directory = Files.createTempDirectory("cassini-model-test").toFile()
        val responder = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val request = socket.getInputStream().bufferedReader()
                        while (!request.readLine().isNullOrEmpty()) { }
                        val header = "HTTP/1.1 $status Response\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                    }
                } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
            }
        }.apply { isDaemon = true; start() }
        try { check("http://127.0.0.1:${server.localPort}/model", directory) }
        finally { server.close(); responder.join(1000); directory.deleteRecursively() }
    }
    private fun artifact(body: ByteArray) = ModelStore.Artifact("model.onnx", body.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })
    @Test fun verifiedBytesAreInstalledAndPartialFilesAreRemoved() {
        val bytes = "model weights".toByteArray()
        withServer(bytes) { url, directory ->
            ModelStore.fetch(url, artifact(bytes), directory) { _, _ -> }
            assertArrayEquals(bytes, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }
    @Test fun aCorrectLengthWithWrongHashCannotReplaceAnExistingModel() {
        val bytes = "model weights".toByteArray()
        withServer("wrong weights".toByteArray()) { url, directory ->
            val original = "existing copy".toByteArray()
            File(directory, "model.onnx").writeBytes(original)
            try { ModelStore.fetch(url, artifact(bytes), directory) { _, _ -> }; fail() }
            catch (error: UserFacingException) { assertEquals(Failure.VERIFY, error.failure) }
            assertArrayEquals(original, File(directory, "model.onnx").readBytes())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }
    @Test fun unavailableModelsReportDownloadFailureAndKeepNoPartial() {
        val bytes = "missing model".toByteArray()
        withServer(bytes, 404) { url, directory ->
            try { ModelStore.fetch(url, artifact(bytes), directory) { _, _ -> }; fail() }
            catch (error: UserFacingException) { assertEquals(Failure.DOWNLOAD, error.failure) }
            assertFalse(File(directory, "model.onnx").exists())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }
    @Test fun anInterruptedDownloadCannotInstallItsResult() {
        val bytes = "model weights".toByteArray()
        withServer(bytes) { url, directory ->
            try {
                Thread.currentThread().interrupt()
                try { ModelStore.fetch(url, artifact(bytes), directory) { _, _ -> }; fail() }
                catch (error: UserFacingException) { assertEquals(Failure.CANCELLED, error.failure) }
                assertFalse(File(directory, "model.onnx").exists())
            } finally { Thread.interrupted() }
        }
    }
}
