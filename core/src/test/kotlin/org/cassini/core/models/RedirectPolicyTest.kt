package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL

class RedirectPolicyTest {
    private val policy = RedirectPolicy.DOWNLOADS
    private val start = URL("https://huggingface.co/repo/resolve/rev/encoder.int8.onnx")

    private fun refused(location: String?, hop: Int = 1): String {
        try {
            policy.next(start, location, hop)
        } catch (error: UserFacingException) {
            assertEquals(Failure.DOWNLOAD, error.failure)
            return error.message.orEmpty()
        }
        fail("Expected $location to be refused")
        error("unreachable")
    }

    @Test fun theRecordedRedirectTargetsAreAllowed() {
        for (target in listOf(
            "https://us.aws.cdn.hf.co/xet-bridge-us/68a0/bccb?Expires=1&Signature=x",
            "https://cas-bridge.xethub.hf.co/xet-bridge-us/a",
            "https://cdn-lfs.huggingface.co/repos/a",
            "https://release-assets.githubusercontent.com/github-production-release-asset/531380835/x",
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/x",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://dist.gocassini.com/models/files/x/model.int8.onnx.zst",
        )) {
            assertEquals(target, policy.next(start, target, 1).toString())
        }
    }

    @Test fun aRelativeLocationStaysOnTheSameHost() {
        val next = policy.next(start, "/api/resolve-cache/models/repo/rev/tokens.txt?x=1", 1)
        assertEquals("https://huggingface.co/api/resolve-cache/models/repo/rev/tokens.txt?x=1", next.toString())
    }

    @Test fun everyCatalogueUrlStartsOnAnAllowedHttpsHost() {
        val urls = (ModelCatalog.consented + ModelCatalog.VAD).flatMap { it.artifacts + listOfNotNull(it.download) }
            .map { it.url }.filter { it.isNotEmpty() }
        assertEquals(7, urls.size)
        urls.forEach { assertTrue(it, policy.allows(URL(it))) }
    }

    @Test fun otherHostsAreRefused() {
        for (target in listOf(
            "https://example.com/model",
            "https://evilhuggingface.co/model",
            "https://huggingface.co.example.com/model",
            "https://hf.co/model",
            "https://githubusercontent.com/model",
            "https://gocassini.com/model",
            "https://other.gocassini.com/model",
            "https://127.0.0.1/model",
        )) {
            refused(target)
        }
    }

    @Test fun plainHttpIsRefusedEvenToAnAllowedHost() {
        assertTrue(refused("http://us.aws.cdn.hf.co/xet").contains("http://us.aws.cdn.hf.co"))
        refused("http://huggingface.co/repo")
        refused("ftp://huggingface.co/repo")
    }

    @Test fun aMissingOrBadLocationIsRefused() {
        refused(null)
        refused("  ")
        refused("notascheme://x")
    }

    @Test fun theSixthRedirectIsRefused() {
        policy.next(start, "https://us.aws.cdn.hf.co/a", RedirectPolicy.MAX_REDIRECTS)
        assertTrue(refused("https://us.aws.cdn.hf.co/a", RedirectPolicy.MAX_REDIRECTS + 1).contains("More than 5"))
    }

    @Test fun hostsMatchWithoutCaseOrATrailingDot() {
        assertTrue(policy.hostAllowed("US.AWS.CDN.HF.CO"))
        assertTrue(policy.hostAllowed("dist.gocassini.com."))
        assertFalse(policy.hostAllowed(""))
        assertFalse(policy.hostAllowed(null))
    }
}
