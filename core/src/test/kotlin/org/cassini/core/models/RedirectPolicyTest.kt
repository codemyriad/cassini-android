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
    private val start = URL("https://dist.gocassini.com/models/files/x/encoder.int8.onnx.zst")

    private fun refused(location: String?, hop: Int = 1, by: RedirectPolicy = policy): String {
        try {
            by.next(start, location, hop)
        } catch (error: UserFacingException) {
            assertEquals(Failure.DOWNLOAD, error.failure)
            return error.message.orEmpty()
        }
        fail("Expected $location to be refused")
        error("unreachable")
    }

    @Test fun onlyTheModelHostIsAllowed() {
        val target = "https://dist.gocassini.com/models/files/x/model.int8.onnx.zst"
        assertEquals(target, policy.next(start, target, 1).toString())
    }

    @Test fun aRelativeLocationStaysOnTheSameHost() {
        val next = policy.next(start, "/models/files/y/tokens.txt.zst?x=1", 1)
        assertEquals("https://dist.gocassini.com/models/files/y/tokens.txt.zst?x=1", next.toString())
    }

    @Test fun everyCatalogueUrlStartsOnAnAllowedHttpsHost() {
        val urls = (ModelCatalog.consented + ModelCatalog.VAD).flatMap { it.artifacts }
            .map { it.download!!.url } + ModelCatalog.DIST_MANIFEST_URL
        assertEquals(8, urls.size)
        urls.forEach { assertTrue(it, policy.allows(URL(it))) }
    }

    @Test fun otherHostsAreRefused() {
        for (target in listOf(
            "https://example.com/model",
            "https://huggingface.co/repo/resolve/rev/encoder.int8.onnx",
            "https://us.aws.cdn.hf.co/xet-bridge-us/a",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://release-assets.githubusercontent.com/github-production-release-asset/x",
            "https://gocassini.com/model",
            "https://other.gocassini.com/model",
            "https://dist.gocassini.com.example.com/model",
            "https://127.0.0.1/model",
        )) {
            refused(target)
        }
    }

    @Test fun aWildcardEntryMatchesSubdomainsOnly() {
        val wildcard = RedirectPolicy(listOf("*.example.com"))
        assertTrue(wildcard.hostAllowed("cdn.example.com"))
        assertTrue(wildcard.hostAllowed("a.b.example.com"))
        assertFalse(wildcard.hostAllowed("example.com"))
        assertFalse(wildcard.hostAllowed("evilexample.com"))
        refused("https://example.com/model", by = wildcard)
    }

    @Test fun plainHttpIsRefusedEvenToAnAllowedHost() {
        assertTrue(refused("http://dist.gocassini.com/x").contains("http://dist.gocassini.com"))
        refused("ftp://dist.gocassini.com/x")
    }

    @Test fun aMissingOrBadLocationIsRefused() {
        refused(null)
        refused("  ")
        refused("notascheme://x")
    }

    @Test fun theSixthRedirectIsRefused() {
        policy.next(start, "https://dist.gocassini.com/a", RedirectPolicy.MAX_REDIRECTS)
        assertTrue(refused("https://dist.gocassini.com/a", RedirectPolicy.MAX_REDIRECTS + 1).contains("More than 5"))
    }

    @Test fun hostsMatchWithoutCaseOrATrailingDot() {
        assertTrue(policy.hostAllowed("DIST.GOCASSINI.COM"))
        assertTrue(policy.hostAllowed("dist.gocassini.com."))
        assertFalse(policy.hostAllowed(""))
        assertFalse(policy.hostAllowed(null))
    }
}
