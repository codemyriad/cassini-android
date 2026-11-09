package io.codemyriad.cassini.core.models

import org.junit.Assert.fail
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

/** Loopback transport and fresh per-test bytes shared by the fetch/resume and completion scenarios. */
abstract class ArtifactFetcherFixture {
    /** One HTTP answer. [sent] bytes of [body] are written, then the connection closes; [length] is what it announces. */
    protected class Answer(
        val status: Int,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
        val sent: Int = body.size,
        val length: Int? = body.size,
        val beforeBody: () -> Unit = {},
    )

    /** A loopback server that records each request's headers and answers with [respond]. */
    protected fun withServer(
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
                        val length = answer.length?.let { "Content-Length: $it\r\n" }.orEmpty()
                        val status = "HTTP/1.1 ${answer.status} Response\r\n$length"
                        val head = "$status${extra}Connection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(head.toByteArray())
                            flush()
                            answer.beforeBody()
                            try {
                                write(answer.body, 0, answer.sent)
                                flush()
                            } catch (_: java.net.SocketException) {
                                // Refusing response headers may close the client before it reads the body.
                            }
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

    protected fun withServer(body: ByteArray, status: Int = 200, check: (String, File) -> Unit) =
        withServer({ Answer(status, body) }) { url, directory, _ -> check(url, directory) }

    protected fun artifact(body: ByteArray) = Artifact(
        "model.onnx",
        body.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    )

    protected fun failure(block: () -> Unit): Failure {
        try { block() } catch (error: UserFacingException) { return error.failure }
        fail("Expected a UserFacingException")
        error("unreachable")
    }

    protected val weights = ByteArray(300_000) { (it * 7 % 253).toByte() }

    /** Answers a Range request with the requested suffix of [body], announcing [total] as the full length. */
    protected fun ranged(
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

}
