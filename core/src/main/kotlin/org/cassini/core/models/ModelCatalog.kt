package org.cassini.core.models

import java.io.File

/**
 * A pinned file: [size] bytes with SHA-256 [sha256]. [download] is the seekable Zstandard copy fetched in its place,
 * with its own pins; its [url] is where the model host keeps it (for the notices and CI: the app fetches the URL that
 * the host's manifest gives, `DistManifest`). A file without [download] is not fetched by the app (tests, CI).
 */
data class Artifact(
    val name: String,
    val size: Long,
    val sha256: String,
    val url: String = "",
    val download: Artifact? = null,
)

/**
 * One installed model, identified by its pinned pair on the model host: the manifest [id] and the hash of its source,
 * [sourceSha256] (the desktop's rule, D53.1). Its [artifacts] go in `<modelRoot>/models/<id>/<sourceSha256>/`
 * ([directory]). It is ready when every artifact has its pinned size and the marker file [markerFile] holds
 * [markerValue], the source hash; the marker is written only after every artifact verified.
 */
data class ModelDescriptor(val id: String, val sourceSha256: String, val artifacts: List<Artifact>) {
    /** The directory under the model root. */
    val directory: String get() = "$MODELS_DIRECTORY/$id/$sourceSha256"

    val markerFile: String get() = MARKER_FILE

    val markerValue: String get() = sourceSha256

    fun directory(filesDir: File) = File(filesDir, directory)

    fun path(filesDir: File, name: String): String = File(directory(filesDir), name).absolutePath

    fun ready(filesDir: File): Boolean {
        val home = directory(filesDir)
        val marker = File(home, markerFile)
        return artifacts.all { File(home, it.name).length() == it.size } && marker.isFile && marker.readText() == markerValue
    }

    /** Bytes fetched over the network to install this model from nothing: the compressed files. */
    val downloadBytes: Long get() = artifacts.sumOf { it.download?.size ?: it.size }

    /** Bytes of the installed files. */
    val artifactBytes: Long get() = artifacts.sumOf { it.size }

    /** The largest compressed download of one file; 0 when no file is compressed. */
    val largestDownloadBytes: Long get() = artifacts.maxOfOrNull { it.download?.size ?: 0L } ?: 0L

    /**
     * Bytes on disk at most while installing: the files are fetched one at a time, and a compressed download is
     * deleted as soon as its decompressed file verified. So the peak is every installed file plus the one download
     * that is being decompressed, at most [largestDownloadBytes].
     */
    val installBytes: Long get() = artifactBytes + largestDownloadBytes

    companion object {
        /** The directory under the model root that holds every model, as on the desktop. */
        const val MODELS_DIRECTORY = "models"

        /** The marker file of every model directory. */
        const val MARKER_FILE = "verified"
    }
}

/**
 * Every model the app installs, pinned as the model host `dist.gocassini.com` publishes it (manifest schema 2,
 * published 2026-10-27): each model by its pair (manifest id, source SHA-256), each file by its decompressed size
 * and SHA-256 and by the size and SHA-256 of its seekable Zstandard download. The app never follows a newer entry
 * of the manifest: a model whose pair or files the manifest no longer lists as pinned here is not downloaded
 * (`DistManifest`). Changing a pin changes the model's directory, so installed models look missing; earlier
 * development builds' directories (`parakeet-ultra/`, `vad/`, `speaker-models/`) are not read (D51).
 */
@Suppress("MagicNumber")
object ModelCatalog {
    /** The model host. Every model file and the manifest come from it, and from nowhere else. */
    const val DIST_HOST = "dist.gocassini.com"

    /** The manifest of the model host, read before each model download to find the pinned files. */
    const val DIST_MANIFEST_URL = "https://$DIST_HOST/manifest.json"

    /** A decompressed file [name] and its download, at the content-addressed path of the model host. */
    private fun dist(name: String, size: Long, sha256: String, compressedSize: Long, compressedSha256: String) =
        Artifact(
            name,
            size,
            sha256,
            download = Artifact(
                "$name.zst",
                compressedSize,
                compressedSha256,
                "https://$DIST_HOST/models/files/$compressedSha256/$name.zst",
            ),
        )

    /**
     * Parakeet Ultra INT8 on the model host (`parakeet-ultra-0.6b-int8`): Cassini's sherpa-onnx `nemo_transducer`
     * export of moondream/parakeet-ultra (revision 73175eb7), a post-train of NVIDIA Parakeet TDT 0.6B v3, quantized
     * to INT8. The export changes only the format; the token table is the one of v3. The revision that provenance
     * and checkpoints name is the model's source SHA-256 on the host, which is also its marker value.
     */
    const val PARAKEET_DIST_ID = "parakeet-ultra-0.6b-int8"
    const val PARAKEET_REVISION = "18cb623004dd6c15999b93cb70b14d8a1f6e8f3a2a97ff733f104bfc3494f6a9"
    const val PARAKEET_PRECISION = "INT8"

    /** The model id that a document's provenance names (`<id> <precision>`), and the name the app shows for it. */
    const val PARAKEET_MODEL_ID = "moondream/parakeet-ultra"
    const val PARAKEET_DISPLAY_NAME = "Parakeet Ultra"

    /**
     * The name the app shows for each speech model id that a document's provenance can name. Only this build's model:
     * the Parakeet TDT 0.6B v3 of 0.1.0 to 0.2.0 made only development-build documents (D51), which show its id.
     */
    val SPEECH_MODEL_NAMES = mapOf(PARAKEET_MODEL_ID to PARAKEET_DISPLAY_NAME)

    val parakeetArtifacts = listOf(
        dist(
            "encoder.int8.onnx",
            652282922,
            "299617fefd4664d1c115b691dcb6413cabcdbcc694cdf3cb4464b72c5fc3ae69",
            452306705,
            "caf7a38f2ad3224c1863564fb6e975a11aa3ae6ad1e7ad0bee5c4f5e018babb4",
        ),
        dist(
            "decoder.int8.onnx",
            11845274,
            "1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922",
            8841708,
            "69e2e91d01225bc552f8b0113630562fc52622cb2a3c61ef4445ee779f06b295",
        ),
        dist(
            "joiner.int8.onnx",
            6355277,
            "8a71aaccfdba3d451775507a889afd2257d2e754463fd81095a8c8f290f924c2",
            3714640,
            "639fda62a71242a8d7cefb6647f2094eb8ac783925ce91cbd047a0de6988f396",
        ),
        dist(
            "tokens.txt",
            93939,
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            45831,
            "5b3811f0c23fefc86835981f0a23ecfb817999df272ad744c597e5b669ea1b4a",
        ),
    )

    val PARAKEET = ModelDescriptor(PARAKEET_DIST_ID, PARAKEET_REVISION, parakeetArtifacts)

    /** Silero VAD for chunked decoding (`silero-vad`), installed on its own: Parakeet is ready without it. */
    val sileroVad = dist(
        "silero_vad.onnx",
        643854,
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        469349,
        "dad26acf8515705fed5510d847c69366cb2710681ec4f87484a2de65ca855a3c",
    )
    val VAD = ModelDescriptor("silero-vad", sileroVad.sha256, listOf(sileroVad))

    /**
     * NVIDIA Nemotron-3-Diarization (`nemotron-3-diarization-int8`), exported to INT8 ONNX (interface v2) for
     * sherpa-onnx's Sortformer runtime. [DIARIZATION_SOURCE_REVISION] is the Hugging Face revision that the
     * manifest's `source_url` names; the source SHA-256 is the export's own hash.
     */
    const val DIARIZATION_SOURCE_REVISION = "f667ed73aee57d40cc39428eb768b4fd87a0a29e"
    val diarizationModel = dist(
        "model.int8.onnx",
        103967426,
        "47c221ea9b4d4e7f6c108bd098e769bc706cdc986c29f6133c93cae335fe6779",
        64915668,
        "794720a59892189484d38a186d2b350018b4194cae55292f501b2849d6313290",
    )
    val DIARIZATION = ModelDescriptor("nemotron-3-diarization-int8", diarizationModel.sha256, listOf(diarizationModel))

    /**
     * CAM++ speaker embeddings (`campplus-voxceleb-16k`), the file of the sherpa-onnx release byte for byte. Its
     * SHA-256 is the model identity of every saved voice (`VoiceStaleness`), so it must not change.
     */
    val voiceprintModel = dist(
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        29596978,
        "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b",
        27138478,
        "c310f60acec22ce27d78bd37736e40015af6473a1e3482224e81434aca5557b0",
    )

    /**
     * The length of a CAM++ print from [voiceprintModel]. A saved voice whose mean has another length cannot be
     * compared and is stale (`VoiceStaleness`). The `:engine` adapter refuses a print of another length; its JVM test
     * uses a fake extraction, so no test runs the real model.
     */
    const val VOICEPRINT_DIM = 512

    val VOICEPRINT = ModelDescriptor("campplus-voxceleb-16k", voiceprintModel.sha256, listOf(voiceprintModel))

    /** The models one consented download installs, in install order. The detector is fetched separately. */
    val consented = listOf(PARAKEET, DIARIZATION, VOICEPRINT)

    /**
     * The hosts a model download may reach (`RedirectPolicy`): the model host alone. An entry `*.example.com` would
     * match every subdomain of example.com but not example.com itself; any other entry matches exactly. The manifest
     * and every file URL it gives must be HTTPS on these hosts, and so must each redirect. PRIVACY.md lists the same
     * entries (LicenseAssetsTest checks it).
     *
     * Recorded on 2026-11-01 with `curl -sI`: the manifest and the model files answer 200 without a redirect, the
     * files with `ETag: "<SHA-256 of the compressed file>"` and `Accept-Ranges: bytes`.
     */
    val DOWNLOAD_HOSTS = listOf(DIST_HOST)

    /** Bytes fetched over the network for each part, which is what the consent dialog quotes. */
    val parakeetBytes = PARAKEET.downloadBytes
    val speakerBytes = DIARIZATION.downloadBytes
    val voiceprintBytes = VOICEPRINT.downloadBytes
    val totalBytes = parakeetBytes + speakerBytes + voiceprintBytes

    /** Whole MiB, rounded to tens from 100 MiB up, as `models_size` is written. */
    fun mebibytes(
        bytes: Long
    ): Long = (bytes / 1048576.0).let { if (it < 100) maxOf(1, Math.round(it)) else Math.round(it / 10) * 10 }
}
