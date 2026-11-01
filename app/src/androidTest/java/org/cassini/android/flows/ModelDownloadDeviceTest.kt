package org.cassini.android.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.luben.zstd.Zstd
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.Artifact
import org.cassini.core.models.DistManifest
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.RedirectPolicy
import org.cassini.data.models.ModelFetcher
import org.cassini.test.CiSmoke
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Collections

/**
 * A model download on the device (coordination D53.1, D4): Android's `HttpURLConnection` and the zstd-jni AAR against a
 * loopback model host that serves a schema 2 manifest and content-addressed Zstandard files, with `Range`, `If-Range`
 * and the ETag of the real host. The JVM tests cover the same code with the desktop JDK; this one proves it on Android.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadDeviceTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "model-download-test")

    private val encoder = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val tokens = "▁a 0\n▁b 1\n".repeat(400).toByteArray()
    private val files = mapOf("encoder.int8.onnx" to encoder, "tokens.txt" to tokens)
    private val packed = files.mapValues { Zstd.compress(it.value) }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pinned(name: String): Artifact {
        val bytes = files.getValue(name)
        val zst = packed.getValue(name)
        val download = Artifact("$name.zst", zst.size.toLong(), sha256(zst))
        return Artifact(name, bytes.size.toLong(), sha256(bytes), download = download)
    }

    private val model = ModelDescriptor("test-model-int8", "e".repeat(64), files.keys.map(::pinned))

    /** Plain HTTP to the loopback host; the app's own policy is HTTPS to dist.gocassini.com only. */
    private val loopback = RedirectPolicy(listOf("127.0.0.1"), httpsOnly = false)

    private data class Request(val path: String, val headers: Map<String, String>)

    private val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private lateinit var server: ServerSocket
    private lateinit var base: String

    @Before fun startHost() {
        root.deleteRecursively()
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}"
        Thread { serve() }.apply { isDaemon = true }.start()
    }

    @After fun stopHost() {
        server.close()
        root.deleteRecursively()
    }

    private fun manifest(): String {
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
        val entry = JSONObject().put("id", model.id).put("source_sha256", model.sourceSha256).put("files", entries)
        return JSONObject().put("schema_version", 2).put("models", JSONArray().put(entry))
            .put("artifacts", artifacts).toString()
    }

    private val bodies: Map<String, Pair<ByteArray, String?>>
        get() = model.artifacts.associate { artifact ->
            val download = artifact.download!!
            "/models/files/${download.sha256}/${artifact.name}.zst" to (packed.getValue(artifact.name) to download.sha256)
        } + ("/manifest.json" to (manifest().toByteArray() to null))

    /** Serves `/manifest.json` and each `.zst` at its content-addressed path, honouring `Range` and `If-Range`. */
    private fun serve() {
        while (!server.isClosed) {
            try {
                server.accept().use(::answer)
            } catch (closed: SocketException) {
                if (!server.isClosed) throw closed
            }
        }
    }

    private fun answer(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val path = reader.readLine().orEmpty().split(" ").getOrElse(1) { "" }
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        requests += Request(path, headers)
        val out = socket.getOutputStream()
        val (body, etag) = bodies[path] ?: run {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val tag = etag?.let { "ETag: \"$it\"\r\n" }.orEmpty()
        val from = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        val matches = headers["if-range"].let { it == null || it == "\"$etag\"" }
        if (from != null && matches) {
            val part = body.copyOfRange(from, body.size)
            val range = "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n"
            val head = "HTTP/1.1 206 Partial Content\r\nContent-Length: ${part.size}\r\n$range$tag"
            out.write("${head}Connection: close\r\n\r\n".toByteArray())
            out.write(part)
        } else {
            val head = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n$tag"
            out.write("${head}Connection: close\r\n\r\n".toByteArray())
            out.write(body)
        }
        out.flush()
    }

    private fun fetcher() = ModelFetcher.network(loopback) { DistManifest.fetch("$base/manifest.json", loopback) }

    @CiSmoke
    @Test fun theManifestLeadsToEachFileWhichIsResumedDecompressedAndVerified() {
        val directory = File(root, model.directory).apply { mkdirs() }
        // An earlier attempt left half of the encoder's download.
        val encoderZst = packed.getValue("encoder.int8.onnx")
        File(directory, "encoder.int8.onnx.zst.part").writeBytes(encoderZst.copyOf(encoderZst.size / 2))

        var last = 0L to false
        fetcher().fetch(model, directory) { bytes, verifying -> last = bytes to verifying }

        for ((name, bytes) in files) assertArrayEquals(name, bytes, File(directory, name).readBytes())
        assertEquals("only the files stay: no .zst, no .part", files.keys, directory.list()!!.toSet())
        assertEquals(model.downloadBytes to true, last)

        val paths = requests.map { it.path }
        assertEquals("/manifest.json", paths.first())
        assertEquals(1 + model.artifacts.size, paths.size)
        val identity = requests.all { it.headers["accept-encoding"] == "identity" }
        assertTrue("every request asks for the bytes as they are", identity)
        val resumed = requests.single { it.path.endsWith("/encoder.int8.onnx.zst") }
        assertEquals("bytes=${encoderZst.size / 2}-", resumed.headers["range"])
        assertEquals("\"${sha256(encoderZst)}\"", resumed.headers["if-range"])
    }

    @CiSmoke
    @Test fun aManifestThatNoLongerListsThePinnedPairDownloadsNothing() {
        val pinnedElsewhere = model.copy(sourceSha256 = "0".repeat(64))
        val directory = File(root, pinnedElsewhere.directory).apply { mkdirs() }
        try {
            fetcher().fetch(pinnedElsewhere, directory) { _, _ -> }
            fail("A revision the manifest does not list must not download")
        } catch (refused: UserFacingException) {
            assertEquals(Failure.VERIFY, refused.failure)
        }
        assertEquals("only the manifest was read", listOf("/manifest.json"), requests.map { it.path })
        assertFalse(File(directory, "encoder.int8.onnx").exists())
    }
}
