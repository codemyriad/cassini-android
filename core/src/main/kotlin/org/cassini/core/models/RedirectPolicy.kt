package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale

/**
 * Which redirects a download follows. `ArtifactFetcher` turns off `HttpURLConnection`'s own redirect handling and
 * asks [next] for each `Location`, so a model download can reach only [hosts], over HTTPS when [httpsOnly], in at
 * most [maxRedirects] hops. The start URL is not checked here: it is the manifest URL of [ModelCatalog] or a file URL
 * that `DistManifest` checked with [allows].
 *
 * Host entries follow [ModelCatalog.DOWNLOAD_HOSTS]: `*.example.com` matches subdomains only, anything else matches
 * the whole host. Matching ignores case and a trailing dot. [ModelCatalog.DOWNLOAD_HOSTS] has only the model host;
 * the manifest's file URLs must pass [allows] too (`DistManifest`).
 */
class RedirectPolicy(
    val hosts: List<String>,
    val httpsOnly: Boolean = true,
    val maxRedirects: Int = MAX_REDIRECTS,
) {
    /** Whether [url] is a target this policy allows. */
    fun allows(url: URL): Boolean {
        val scheme = url.protocol.lowercase(Locale.ROOT)
        val schemeAllowed = scheme == "https" || (!httpsOnly && scheme == "http")
        return schemeAllowed && hostAllowed(url.host)
    }

    fun hostAllowed(host: String?): Boolean {
        val name = host.orEmpty().lowercase(Locale.ROOT).removeSuffix(".")
        if (name.isEmpty()) return false
        return hosts.any { entry ->
            val pattern = entry.lowercase(Locale.ROOT)
            if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern
        }
    }

    /**
     * The URL redirect number [hop] (from 1) leads to: [location] resolved against [from]. Fails with
     * [Failure.DOWNLOAD] when there is no usable location, when [hop] is past [maxRedirects], or when the target is
     * not allowed (another host, or plain HTTP).
     */
    fun next(from: URL, location: String?, hop: Int): URL {
        val target = location?.takeIf { it.isNotBlank() }?.let { resolve(from, it.trim()) }
        val refusal = when {
            hop > maxRedirects -> "More than $maxRedirects redirects"
            location.isNullOrBlank() -> "Redirect without a location"
            target == null -> "Bad redirect location"
            !allows(target) -> "Redirect to ${target.protocol}://${target.host} is not allowed"
            else -> null
        }
        if (refusal != null || target == null) throw UserFacingException(Failure.DOWNLOAD, refusal ?: "Redirect")
        return target
    }

    private fun resolve(from: URL, location: String): URL? = try {
        URL(from, location)
    } catch (_: MalformedURLException) {
        null
    }

    companion object {
        const val MAX_REDIRECTS = 5

        /** The policy for model downloads: HTTPS to [ModelCatalog.DOWNLOAD_HOSTS], at most 5 hops. */
        val DOWNLOADS = RedirectPolicy(ModelCatalog.DOWNLOAD_HOSTS)
    }
}
