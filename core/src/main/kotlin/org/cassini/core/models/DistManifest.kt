package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL

/**
 * The manifest of the model host ([ModelCatalog.DIST_MANIFEST_URL], schema version 2), read before a model download
 * to find where the pinned files are. It decides nothing else: a model is installed by its pinned pair
 * ([ModelDescriptor.id], [ModelDescriptor.sourceSha256]) and its files by the pins compiled into [ModelCatalog], as
 * the desktop does (D53.1). The app never follows a newer entry.
 *
 * [urls] refuses with [Failure.MODEL_UNAVAILABLE] (the "Model not available" banner: update the app) when the host no
 * longer offers this build's model: the manifest does not list the pair, lists another set of required files for it,
 * or a file's artifact differs from the pins in any of its compressed or decompressed size and SHA-256 (or is gone);
 * [parse] refuses a manifest of another schema version the same way. A manifest that is not one (not JSON, members
 * missing) or a file whose URL the download's [RedirectPolicy] does not allow (HTTPS on dist.gocassini.com) is
 * refused with [Failure.VERIFY] ("File check failed"). The files are verified again after the download.
 *
 * Schema 2: `models[]` gives `{id, source_sha256, files[{path, artifact, required}]}`, where `artifact` is the path
 * `models/files/<compressed sha256>/<path>.zst`; `artifacts[]` gives `{id, url, size, sha256, uncompressed_size,
 * uncompressed_sha256}` with `id` equal to the compressed SHA-256.
 */
class DistManifest private constructor(private val json: JSONObject) {
    /**
     * The URL of each artifact of [model], by artifact name, as the manifest gives it, once every check above passed.
     * [allowed] is the policy of the download that follows.
     */
    fun urls(model: ModelDescriptor, allowed: RedirectPolicy = RedirectPolicy.DOWNLOADS): Map<String, String> = try {
        resolve(model, allowed)
    } catch (error: JSONException) {
        throw UserFacingException(Failure.VERIFY, "Manifest: ${error.message}").apply { initCause(error) }
    }

    private fun resolve(model: ModelDescriptor, allowed: RedirectPolicy): Map<String, String> {
        val pair = "${model.id} ${model.sourceSha256}"
        val entry = objects(json.getJSONArray("models"))
            .singleOrNull { it.getString("id") == model.id && it.getString("source_sha256") == model.sourceSha256 }
            ?: unavailable("The manifest does not list $pair")
        val files = objects(entry.getJSONArray("files"))
        val required = files.filter { it.optBoolean("required", true) }.map { it.getString("path") }.toSet()
        unavailableUnless(required == model.artifacts.map { it.name }.toSet(), "$pair: the manifest lists $required")
        val artifacts = objects(json.getJSONArray("artifacts")).associateBy { it.getString("id") }
        return model.artifacts.associate { pinned ->
            val download = pinned.download ?: refuse("$pair: ${pinned.name} has no pinned download")
            val file = files.singleOrNull { it.getString("path") == pinned.name }
                ?: unavailable("$pair: no ${pinned.name}")
            val path = "models/files/${download.sha256}/${pinned.name}.zst"
            val listed = file.getString("artifact")
            unavailableUnless(listed == path, "$pair: ${pinned.name} is $listed")
            val artifact = artifacts[download.sha256] ?: unavailable("$pair: no artifact ${download.sha256}")
            val matches = artifact.getLong("size") == download.size &&
                artifact.getString("sha256") == download.sha256 &&
                artifact.getLong("uncompressed_size") == pinned.size &&
                artifact.getString("uncompressed_sha256") == pinned.sha256
            unavailableUnless(matches, "$pair: ${pinned.name} differs from the pins")
            val url = artifact.getString("url")
            refuseUnless(allowed.allows(location(url)), "$pair: ${pinned.name} is at $url")
            pinned.name to url
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2

        /** The manifest is about 45 KB; a longer answer is not read past this. */
        const val MAX_BYTES = 1L shl 20

        /**
         * The manifest in [text]; fails with [Failure.MODEL_UNAVAILABLE] for a manifest of another schema version (the
         * host moved on: update the app), and with [Failure.VERIFY] for anything that is not a manifest.
         */
        fun parse(text: String): DistManifest {
            val json = try {
                JSONObject(text)
            } catch (error: JSONException) {
                throw UserFacingException(Failure.VERIFY, "Manifest: ${error.message}").apply { initCause(error) }
            }
            val version = json.optInt("schema_version", -1)
            refuseUnless(version > 0, "Manifest without a schema version")
            unavailableUnless(version == SCHEMA_VERSION, "Manifest schema $version")
            return DistManifest(json)
        }

        /**
         * Downloads and parses the manifest at [url] as [userAgent] (the app, D54), following only the redirects
         * [redirects] allows.
         */
        fun fetch(
            userAgent: String,
            url: String = ModelCatalog.DIST_MANIFEST_URL,
            redirects: RedirectPolicy = RedirectPolicy.DOWNLOADS,
        ): DistManifest = parse(ArtifactFetcher.read(url, MAX_BYTES, userAgent, redirects).toString(Charsets.UTF_8))

        private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)

        private fun location(url: String): URL = try {
            URL(url)
        } catch (error: MalformedURLException) {
            throw UserFacingException(Failure.VERIFY, "Manifest URL $url").apply { initCause(error) }
        }

        private fun refuse(reason: String): Nothing = throw UserFacingException(Failure.VERIFY, reason)

        private fun refuseUnless(condition: Boolean, reason: String) {
            if (!condition) refuse(reason)
        }

        private fun unavailable(reason: String): Nothing = throw UserFacingException(Failure.MODEL_UNAVAILABLE, reason)

        private fun unavailableUnless(condition: Boolean, reason: String) {
            if (!condition) unavailable(reason)
        }
    }
}
