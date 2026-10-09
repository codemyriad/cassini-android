package org.cassini.android

import com.github.luben.zstd.Zstd
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.Artifact
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

class InstalledModelsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sized(file: File, size: Long) {
        file.parentFile!!.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun installParakeet() {
        val directory = File(folder.root, "parakeet-v3")
        ModelCatalog.parakeetArtifacts.forEach { sized(File(directory, it.name), it.size) }
        File(directory, "verified").writeText(ModelCatalog.PARAKEET_REVISION)
    }
    private fun installSpeakers() {
        val directory = File(folder.root, "speaker-models")
        sized(File(directory, ModelCatalog.diarizationModel.name), ModelCatalog.diarizationModel.size)
        File(directory, "verified").writeText(ModelCatalog.diarizationModel.sha256)
    }
    private fun installVoiceprint() {
        val directory = File(folder.root, "speaker-models")
        sized(File(directory, "voiceprint-campplus.onnx"), ModelCatalog.voiceprintModel.size)
        File(directory, "verified-voiceprint").writeText(ModelCatalog.voiceprintModel.sha256)
    }

    @Test fun readinessNeedsAllThreeModelsAndMissingBytesShrinkAsTheyArrive() {
        val models = InstalledModels(folder.root)
        assertFalse(models.ready())
        assertEquals(ModelCatalog.totalBytes, models.missingBytes())
        installParakeet()
        installSpeakers()
        assertFalse(models.ready())
        assertEquals(ModelCatalog.voiceprintModel.size, models.missingBytes())
        assertEquals(28L, ModelCatalog.mebibytes(models.missingBytes()))
        installVoiceprint()
        assertTrue(models.ready())
        assertEquals(0L, models.missingBytes())
    }

    @Test fun onceParakeetIsInstalledAMissingOptionalModelDoesNotAskAgain() {
        val models = InstalledModels(folder.root)
        assertFalse(models.consented())
        installParakeet()
        assertTrue(models.consented())
        assertFalse(models.ready())
    }

    @Test fun aVoiceprintMarkerForAnotherModelIsNotReady() {
        installVoiceprint()
        assertTrue(InstalledModels(folder.root).ready(ModelCatalog.VOICEPRINT))
        File(folder.root, "speaker-models/verified-voiceprint").writeText("other")
        assertFalse(InstalledModels(folder.root).ready(ModelCatalog.VOICEPRINT))
    }

    @Test fun parakeetFilesAreTheInt8GraphsAndTokensOfTheInstall() {
        val files = InstalledModels(folder.root).parakeet()
        val directory = File(folder.root, "parakeet-v3")
        assertEquals(File(directory, "encoder.int8.onnx").absolutePath, files.encoder)
        assertEquals(File(directory, "decoder.int8.onnx").absolutePath, files.decoder)
        assertEquals(File(directory, "joiner.int8.onnx").absolutePath, files.joiner)
        assertEquals(File(directory, "tokens.txt").absolutePath, files.tokens)
        val diarizer = File(folder.root, "speaker-models/nemotron-3-diarization.int8.onnx")
        assertEquals(diarizer.absolutePath, InstalledModels(folder.root).path(ModelCatalog.DIARIZATION))
    }

    @Test fun vadIsReadyOnlyWithItsFullFileAndVerificationMarker() {
        val models = InstalledModels(folder.root)
        val path = File(folder.root, "vad/silero_vad.onnx").absolutePath
        assertEquals(path, models.path(ModelCatalog.VAD))
        assertNull(models.detector())
        val directory = File(folder.root, "vad").apply { mkdirs() }
        File(directory, "silero_vad.onnx").writeBytes(ByteArray(ModelCatalog.sileroVad.size.toInt()))
        assertNull(models.detector())
        File(directory, "verified").writeText("stale")
        assertNull(models.detector())
        File(directory, "verified").writeText(ModelCatalog.sileroVad.sha256)
        assertEquals(path, models.detector())
        File(directory, "silero_vad.onnx").writeBytes(ByteArray(10))
        assertNull(models.detector())
        // A Parakeet install does not depend on the detector.
        assertFalse(ModelCatalog.parakeetArtifacts.any { it.name == ModelCatalog.sileroVad.name })
    }

    /** Serves [bodies] by artifact name on a loopback server and installs through it. */
    private fun withServer(
        bodies: Map<String, ByteArray>,
        usableSpace: (File) -> Long = File::getUsableSpace,
        check: (InstalledModels) -> Unit,
    ) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "" }.removePrefix("/")
                        while (!reader.readLine().isNullOrEmpty()) { /* headers */ }
                        val body = bodies[path]
                        val head = when (body) {
                            null -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n"
                            else -> "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n"
                        }
                        socket.getOutputStream().apply {
                            write("${head}Connection: close\r\n\r\n".toByteArray())
                            if (body != null) write(body)
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
            check(InstalledModels(folder.root, usableSpace) { "http://127.0.0.1:${server.localPort}/${it.name}" })
        } finally {
            server.close()
            responder.join(1000)
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun artifact(name: String, bytes: ByteArray) = Artifact(name, bytes.size.toLong(), sha256(bytes))

    private fun failure(block: () -> Unit): Failure {
        try { block() } catch (error: UserFacingException) { return error.failure }
        fail("Expected a UserFacingException")
        error("unreachable")
    }

    @Test fun aFailedInstallDeletesTheMarkerFirstAndLeavesTheModelNotReady() {
        installVoiceprint()
        val models = InstalledModels(folder.root)
        assertTrue(models.ready(ModelCatalog.VOICEPRINT))
        // The installed file is the right size but not the pinned bytes, so it is fetched again; the server sends
        // a short file, which fails verification.
        val wrong = "not the voiceprint model".toByteArray()
        withServer(mapOf(ModelCatalog.voiceprintModel.name to wrong)) { served ->
            assertEquals(Failure.VERIFY, failure { served.install(ModelCatalog.VOICEPRINT) { } })
        }
        assertFalse(File(folder.root, "speaker-models/verified-voiceprint").exists())
        assertFalse(models.ready(ModelCatalog.VOICEPRINT))
    }

    @Test fun filesAreFetchedInOrderWithProgressAndMarkedLast() {
        val bytes = listOf("first weights".toByteArray(), "second, longer weights".toByteArray())
        val artifacts = listOf(artifact("a.onnx", bytes[0]), artifact("b.onnx", bytes[1]))
        val model = ModelDescriptor("test-model", artifacts, "verified", "pin")
        val seen = mutableListOf<InstalledModels.Progress>()
        withServer(mapOf("a.onnx" to bytes[0], "b.onnx" to bytes[1])) { models ->
            models.install(model) {
                assertFalse("The marker is written last", File(folder.root, "test-model/verified").exists())
                seen += it
            }
            assertTrue(models.ready(model))
        }
        assertArrayEquals(bytes[1], File(folder.root, "test-model/b.onnx").readBytes())
        assertEquals("pin", File(folder.root, "test-model/verified").readText())
        assertEquals(100, seen.last().percent)
        assertEquals(seen.map { it.percent }.sorted(), seen.map { it.percent })
    }

    @Test fun theCompressedDiarizerIsVerifiedDecompressedAndMarked() {
        val weights = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val compressed = Zstd.compress(weights)
        val model = ModelCatalog.DIARIZATION.copy(
            artifacts = listOf(artifact(ModelCatalog.diarizationModel.name, weights)),
            markerValue = sha256(weights),
            download = artifact(ModelCatalog.diarizationDownload.name, compressed),
        )
        val seen = mutableListOf<InstalledModels.Progress>()
        val directory = File(folder.root, "speaker-models")
        val decompressed = File(directory, ModelCatalog.diarizationModel.name)
        var decompressedAtFirstCheck: Boolean? = null
        withServer(mapOf(ModelCatalog.diarizationDownload.name to compressed)) { models ->
            models.install(model) {
                if (it.verifying && decompressedAtFirstCheck == null) decompressedAtFirstCheck = decompressed.exists()
                seen += it
            }
            assertTrue(models.ready(model))
        }
        // "Verifying" shows while the download is checked and decompressed, not only once that is over.
        assertEquals(false, decompressedAtFirstCheck)
        assertEquals(InstalledModels.Progress(100, true), seen.first { it.verifying })
        assertArrayEquals(weights, File(directory, ModelCatalog.diarizationModel.name).readBytes())
        assertFalse("The compressed file is not kept", File(directory, ModelCatalog.diarizationDownload.name).exists())
        assertEquals(sha256(weights), File(folder.root, "speaker-models/verified").readText())
        assertTrue(seen.last().verifying)
        assertEquals(100, seen.last().percent)
    }

    @Test fun theParakeetSpaceCheckCountsAKeptPartAsFetched() {
        val directory = File(folder.root, "parakeet-v3")
        val total = ModelCatalog.parakeetArtifacts.sumOf { it.size }
        val encoder = ModelCatalog.parakeetArtifacts.first { it.name == "encoder.int8.onnx" }
        val tokens = ModelCatalog.parakeetArtifacts.first { it.name == "tokens.txt" }
        val models = InstalledModels(folder.root)
        assertEquals(total, models.bytesToFetch(ModelCatalog.PARAKEET))
        // A finished file and most of the encoder, kept from an interrupted attempt.
        sized(File(directory, tokens.name), tokens.size)
        val kept = encoder.size - 1024 * 1024
        sized(File(directory, "${encoder.name}.part"), kept)
        val remaining = total - tokens.size - kept
        assertEquals(remaining, models.bytesToFetch(ModelCatalog.PARAKEET))
        // Room for what is left but not for the whole model again: the install gets past the check and reaches
        // the server, which has nothing.
        val margin = 128 * 1024 * 1024L
        withServer(emptyMap(), usableSpace = { remaining + margin + 1 }) { served ->
            assertEquals(Failure.DOWNLOAD, failure { served.install(ModelCatalog.PARAKEET) { } })
        }
        assertEquals("A 404 keeps the part", kept, File(directory, "${encoder.name}.part").length())
        withServer(emptyMap(), usableSpace = { remaining + margin }) { served ->
            assertEquals(Failure.SPACE, failure { served.install(ModelCatalog.PARAKEET) { } })
        }
    }

    /** Installs [model], whose file is already in place, and interrupts the thread as the fetch finishes. */
    private fun installInterrupted(model: ModelDescriptor, bytes: ByteArray): Failure? {
        File(model.directory(folder.root).apply { mkdirs() }, model.artifacts.single().name).writeBytes(bytes)
        return try {
            InstalledModels(folder.root).install(model) { if (it.verifying) Thread.currentThread().interrupt() }
            null
        } catch (error: UserFacingException) {
            error.failure
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun aCancelledSpeakerInstallWritesNoMarker() {
        val bytes = "voiceprint weights".toByteArray()
        val voiceprint = ModelCatalog.VOICEPRINT.copy(
            artifacts = listOf(artifact(ModelCatalog.voiceprintModel.name, bytes)),
            markerValue = sha256(bytes),
        )
        assertEquals(Failure.CANCELLED, installInterrupted(voiceprint, bytes))
        assertFalse(File(folder.root, "speaker-models/verified-voiceprint").exists())
    }

    @Test fun aCancelledDetectorInstallIsStillMarkedAsIn010() {
        val bytes = "detector weights".toByteArray()
        val vad = ModelCatalog.VAD.copy(
            artifacts = listOf(artifact(ModelCatalog.sileroVad.name, bytes)),
            markerValue = sha256(bytes),
        )
        assertNull(installInterrupted(vad, bytes))
        assertEquals(sha256(bytes), File(folder.root, "vad/verified").readText())
    }

    @Test fun theStatedDownloadSizeMatchesTheArtifacts() {
        val stated = File("src/main/res/values/strings.xml").readText()
            .let { Regex("""name="models_size">(\d+) MiB<""").find(it)!!.groupValues[1].toLong() }
        assertEquals(stated, ModelCatalog.mebibytes(ModelCatalog.totalBytes))
        val italian = File("src/main/res/values-it/strings.xml").readText()
        assertTrue(italian.contains("""name="models_size">$stated MiB<"""))
    }
}
