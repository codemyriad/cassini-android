package org.cassini.data.models

import com.github.luben.zstd.Zstd
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.Artifact
import org.cassini.core.models.DistManifest
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.RedirectPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections

/**
 * [ModelFetcher.network] against a loopback model host: the manifest decides where each pinned file is, and each file
 * comes as its own Zstandard object, verified before and after decompression.
 */
class ModelFetcherNetworkTest {
    @get:Rule val folder = TemporaryFolder()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private val encoder = ByteArray(200_000) { (it * 13 % 241).toByte() }
    private val tokens = "a 0\nb 1\n".repeat(500).toByteArray()
    private val files = mapOf("encoder.onnx" to encoder, "tokens.txt" to tokens)
    private val packed = files.mapValues { Zstd.compress(it.value) }

    private fun pinned(name: String) = Artifact(
        name,
        files.getValue(name).size.toLong(),
        sha256(files.getValue(name)),
        download = Artifact("$name.zst", packed.getValue(name).size.toLong(), sha256(packed.getValue(name))),
    )

    private val model = ModelDescriptor(
        "test-model",
        "f".repeat(64),
        listOf(pinned("encoder.onnx"), pinned("tokens.txt")),
    )

    /** Plain HTTP to the loopback server, as the test host is not HTTPS. */
    private val loopback = RedirectPolicy(listOf("127.0.0.1"), httpsOnly = false)

    /** A manifest of schema 2 that lists [model] with its files at [base]. */
    private fun manifest(base: String, sourceSha256: String = model.sourceSha256): DistManifest {
        val artifacts = JSONArray()
        val entries = JSONArray()
        for (artifact in model.artifacts) {
            val download = artifact.download!!
            val path = "models/files/${download.sha256}/${artifact.name}.zst"
            entries.put(JSONObject().put("path", artifact.name).put("artifact", path).put("required", true))
            artifacts.put(
                JSONObject().put("id", download.sha256).put("url", "$base/$path").put("size", download.size)
                    .put("sha256", download.sha256).put("uncompressed_size", artifact.size)
                    .put("uncompressed_sha256", artifact.sha256),
            )
        }
        val entry = JSONObject().put("id", model.id).put("source_sha256", sourceSha256).put("files", entries)
        val json = JSONObject()
            .put("schema_version", 2)
            .put("models", JSONArray().put(entry))
            .put("artifacts", artifacts)
        return DistManifest.parse(json.toString())
    }

    /** Serves each compressed file at its content-addressed path; records the requested paths. */
    private fun withHost(check: (String, List<String>) -> Unit) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val byPath = model.artifacts.associate { artifact ->
            "/models/files/${artifact.download!!.sha256}/${artifact.name}.zst" to packed.getValue(artifact.name)
        }
        val responder = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(" ").getOrElse(1) { "" }
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        requests += path
                        val body = byPath[path]
                        val status = if (body == null) "404 Not Found" else "200 OK"
                        val head = "HTTP/1.1 $status\r\nContent-Length: ${body?.size ?: 0}\r\n"
                        socket.getOutputStream().apply {
                            write("${head}Connection: close\r\n\r\n".toByteArray())
                            body?.let(::write)
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
            check("http://127.0.0.1:${server.localPort}", requests)
        } finally {
            server.close()
            responder.join(1000)
        }
    }

    @Test fun eachFileIsFetchedFromWhereTheManifestSaysAndDecompressed() = withHost { base, requests ->
        val directory = folder.newFolder("model")
        val seen = mutableListOf<Pair<Long, Boolean>>()
        ModelFetcher.network(loopback) { manifest(base) }.fetch(model, directory) { bytes, verifying ->
            seen += bytes to verifying
        }
        for ((name, bytes) in files) assertArrayEquals(bytes, File(directory, name).readBytes())
        assertEquals(setOf("encoder.onnx", "tokens.txt"), directory.list()!!.toSet())
        assertEquals(model.artifacts.map { "/models/files/${it.download!!.sha256}/${it.name}.zst" }, requests.toList())
        // The progress counts the compressed bytes of both files and ends with all of them.
        assertEquals(model.downloadBytes to true, seen.last())
        assertTrue(seen.all { it.first in 0..model.downloadBytes })
    }

    @Test fun aManifestWithoutThePinnedPairFetchesNothing() = withHost { base, requests ->
        val directory = folder.newFolder("model")
        try {
            ModelFetcher.network(loopback) { manifest(base, sourceSha256 = "0".repeat(64)) }
                .fetch(model, directory) { _, _ -> }
            fail("A model the manifest does not list as pinned must not download")
        } catch (error: UserFacingException) {
            assertEquals(Failure.VERIFY, error.failure)
        }
        assertTrue(requests.isEmpty())
        assertFalse(File(directory, "encoder.onnx").exists())
    }

    @Test fun aFileUrlOffTheAllowedHostsFetchesNothing() = withHost { base, requests ->
        val directory = folder.newFolder("model")
        try {
            // The download policy of the app: HTTPS to dist.gocassini.com only.
            ModelFetcher.network { manifest(base) }.fetch(model, directory) { _, _ -> }
            fail("A loopback URL is not on the model host")
        } catch (error: UserFacingException) {
            assertEquals(Failure.VERIFY, error.failure)
        }
        assertTrue(requests.isEmpty())
    }
}
