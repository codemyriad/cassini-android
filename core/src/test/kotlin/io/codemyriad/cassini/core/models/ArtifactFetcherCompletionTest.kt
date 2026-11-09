package io.codemyriad.cassini.core.models

import io.codemyriad.cassini.core.Failure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArtifactFetcherCompletionTest : ArtifactFetcherFixture() {
    @Test fun publicDigestKeepsIoFailuresForInstalledModelValidation() {
        val directory = Files.createTempDirectory("cassini-unreadable-model").toFile()
        try {
            val error = runCatching { ArtifactFetcher.digest(directory) }.exceptionOrNull()
            assertTrue(error is IOException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aTruncatedAnswerWithoutLengthKeepsItsPrefixForResume() {
        val resumed = ranged(weights)
        withServer({ headers ->
            if (headers["range"] == null) Answer(200, weights, sent = 100_000, length = null) else resumed(headers)
        }) { url, directory, requests ->
            assertEquals(Failure.DOWNLOAD, fetching(url, directory))
            assertEquals(100_000L, File(directory, "model.onnx.part").length())
            assertFalse(File(directory, "model.onnx").exists())
            ArtifactFetcher.fetch(url, artifact(weights), directory, AGENT) { _, _ -> }
            assertEquals("bytes=100000-", requests.last()["range"])
            assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
        }
    }

    @Test fun aRangeCannotDeliverBytesPastItsDeclaredEnd() {
        withServer({
            Answer(
                206,
                weights.copyOfRange(100_000, weights.size),
                mapOf("Content-Range" to "bytes 100000-199999/${weights.size}"),
                length = null,
            )
        }) { url, directory, _ ->
            File(directory, "model.onnx.part").writeBytes(weights.copyOf(100_000))
            assertEquals(Failure.VERIFY, fetching(url, directory))
            assertFalse(File(directory, "model.onnx").exists())
            assertFalse(File(directory, "model.onnx.part").exists())
        }
    }

    @Test fun inconsistentLengthIsRefusedBeforeWaitingForTheBody() {
        val headersSent = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        withServer({
            Answer(200, weights, length = weights.size + 1, beforeBody = {
                headersSent.countDown()
                check(releaseBody.await(5, TimeUnit.SECONDS))
            })
        }) { url, directory, _ ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val result = pool.submit<Failure> { fetching(url, directory) }
                assertTrue(headersSent.await(5, TimeUnit.SECONDS))
                assertEquals(Failure.VERIFY, result.get(2, TimeUnit.SECONDS))
                assertFalse(File(directory, "model.onnx").exists())
                assertFalse(File(directory, "model.onnx.part").exists())
            } finally {
                releaseBody.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun aLocalOutputFailureIsInstallRatherThanNetwork() {
        withServer(weights) { url, directory ->
            File(directory, "model.onnx.part").mkdir()
            assertEquals(Failure.INSTALL, fetching(url, directory))
            assertFalse(File(directory, "model.onnx").exists())
        }
    }

    @Test fun aSlowValidResponseInstallsOnlyAfterItsBodyArrives() {
        val headersSent = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        withServer({
            Answer(200, weights, beforeBody = {
                headersSent.countDown()
                check(releaseBody.await(5, TimeUnit.SECONDS))
            })
        }) { url, directory, _ ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val result = pool.submit { ArtifactFetcher.fetch(url, artifact(weights), directory, AGENT) { _, _ -> } }
                assertTrue(headersSent.await(5, TimeUnit.SECONDS))
                assertFalse(result.isDone)
                assertFalse(File(directory, "model.onnx").exists())
                releaseBody.countDown()
                result.get(5, TimeUnit.SECONDS)
                assertArrayEquals(weights, File(directory, "model.onnx").readBytes())
            } finally {
                releaseBody.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun aFailedFinalMoveRetainsVerifiedBytesForANetworkFreeRetry() {
        withServer({ Answer(200, weights) }) { url, directory, requests ->
            val destination = File(directory, "model.onnx").apply { mkdir() }
            val obstruction = File(destination, "occupied").apply { writeText("Keep this") }
            assertEquals(Failure.INSTALL, fetching(url, directory))
            assertArrayEquals(weights, File(directory, "model.onnx.part").readBytes())
            assertTrue(destination.isDirectory)
            obstruction.delete()
            destination.delete()
            ArtifactFetcher.fetch(url, artifact(weights), directory, AGENT) { _, _ -> }
            assertEquals(1, requests.size)
            assertArrayEquals(weights, destination.readBytes())
        }
    }

    private fun fetching(url: String, directory: File) =
        failure { ArtifactFetcher.fetch(url, artifact(weights), directory, AGENT) { _, _ -> } }

    private companion object {
        const val AGENT = "CassiniTalk/0.0.0-test (JVM; build 0)"
    }
}
