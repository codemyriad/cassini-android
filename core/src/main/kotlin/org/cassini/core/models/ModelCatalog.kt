package org.cassini.core.models

import java.io.File

/** A pinned file: [size] bytes with SHA-256 [sha256], fetched from [url] (empty for a file produced on the phone). */
data class Artifact(val name: String, val size: Long, val sha256: String, val url: String = "")

/**
 * One installed model: the [artifacts] in `filesDir/[directory]`. It is ready when every artifact has its pinned size
 * and the marker file [markerFile] holds [markerValue]; the marker is written only after every artifact verified.
 * [download] is the compressed file fetched in place of the single artifact, when there is one.
 */
data class ModelDescriptor(
    val directory: String,
    val artifacts: List<Artifact>,
    val markerFile: String,
    val markerValue: String,
    val download: Artifact? = null,
) {
    fun directory(filesDir: File) = File(filesDir, directory)

    fun path(filesDir: File, name: String): String = File(directory(filesDir), name).absolutePath

    fun ready(filesDir: File): Boolean {
        val home = directory(filesDir)
        val marker = File(home, markerFile)
        return artifacts.all { File(home, it.name).length() == it.size } && marker.isFile && marker.readText() == markerValue
    }

    /** Bytes fetched over the network to install this model from nothing. */
    val downloadBytes: Long get() = download?.size ?: artifacts.sumOf { it.size }

    /** Bytes of the installed files. */
    val artifactBytes: Long get() = artifacts.sumOf { it.size }
}

/**
 * Every model the app installs, with the exact pins, URLs, directory names and marker semantics of 0.1.0
 * (`ModelStore`, `DiarizationModels`, `VoiceprintModel`, `ModelBundle`). Changing a pin, a directory or a marker
 * value makes installed models look missing or, worse, look ready when they are not. The sizes are pinned data.
 */
@Suppress("MagicNumber")
object ModelCatalog {
    /** Parakeet TDT 0.6B v3 INT8 on Hugging Face. Its marker `parakeet-v3/verified` holds this revision. */
    const val PARAKEET_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    const val PARAKEET_REPOSITORY = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    const val PARAKEET_PRECISION = "INT8"

    private fun huggingFace(name: String) = "https://huggingface.co/$PARAKEET_REPOSITORY/resolve/$PARAKEET_REVISION/$name"

    val parakeetArtifacts = listOf(
        Artifact(
            "encoder.int8.onnx",
            652184281,
            "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
            huggingFace("encoder.int8.onnx"),
        ),
        Artifact(
            "decoder.int8.onnx",
            11845275,
            "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e",
            huggingFace("decoder.int8.onnx"),
        ),
        Artifact(
            "joiner.int8.onnx",
            6355277,
            "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3",
            huggingFace("joiner.int8.onnx"),
        ),
        Artifact(
            "tokens.txt",
            93939,
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            huggingFace("tokens.txt")
        ),
    )
    val PARAKEET = ModelDescriptor("parakeet-v3", parakeetArtifacts, "verified", PARAKEET_REVISION)

    /** Silero VAD for chunked decoding, installed on its own so an existing Parakeet install stays ready without it. */
    const val SILERO_VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    val sileroVad =
        Artifact(
            "silero_vad.onnx",
            643854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            SILERO_VAD_URL
        )
    val VAD = ModelDescriptor("vad", listOf(sileroVad), "verified", sileroVad.sha256)

    /** NVIDIA Nemotron-3-Diarization, exported to INT8 ONNX for sherpa-onnx's Sortformer runtime. */
    const val DIARIZATION_SOURCE_REVISION = "f667ed73aee57d40cc39428eb768b4fd87a0a29e"
    const val DIARIZATION_URL =
        "https://dist.gocassini.com/models/files/51249515e4cb49dc00c5d5dc0a41ac59aa91157d689ec9586b3f4bf567c3f602/model.int8.onnx.zst"
    val diarizationModel = Artifact(
        "nemotron-3-diarization.int8.onnx",
        103941758,
        "01d1f893f394cc0418ae78425d216ed62c334c0125e650135332a7a8e595f168",
    )

    /** Seekable Zstandard copy of [diarizationModel] on Cassini's model host, verified before decompression. */
    val diarizationDownload = Artifact(
        "nemotron-3-diarization.int8.onnx.zst",
        64982914,
        "51249515e4cb49dc00c5d5dc0a41ac59aa91157d689ec9586b3f4bf567c3f602",
        DIARIZATION_URL,
    )
    val DIARIZATION = ModelDescriptor(
        "speaker-models",
        listOf(diarizationModel),
        "verified",
        diarizationModel.sha256,
        diarizationDownload,
    )

    /** CAM++ speaker embeddings, fetched verbatim from the pinned sherpa-onnx release. Shares `speaker-models/`. */
    const val VOICEPRINT_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    val voiceprintModel = Artifact(
        "voiceprint-campplus.onnx",
        29596978,
        "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b",
        VOICEPRINT_URL,
    )

    /**
     * The length of a CAM++ print from [voiceprintModel]. A saved voice whose mean has another length cannot be
     * compared and is stale (`VoiceStaleness`). The `:engine` adapter refuses a print of another length; its JVM test
     * uses a fake extraction, so no test runs the real model.
     */
    const val VOICEPRINT_DIM = 512

    val VOICEPRINT =
        ModelDescriptor("speaker-models", listOf(voiceprintModel), "verified-voiceprint", voiceprintModel.sha256)

    /** The models one consented download installs, in install order. The detector is fetched separately. */
    val consented = listOf(PARAKEET, DIARIZATION, VOICEPRINT)

    /**
     * The hosts a model download may be redirected to (`RedirectPolicy`). An entry `*.example.com` matches every
     * subdomain of example.com but not example.com itself; any other entry matches exactly. Downloads start only at
     * the catalogue URLs (huggingface.co, github.com, dist.gocassini.com), and each redirect must be HTTPS to one of
     * these hosts. PRIVACY.md lists the same entries (LicenseAssetsTest checks it).
     *
     * Redirects recorded on 2026-10-09 with `curl -sIL --max-redirs 5` for every catalogue URL (query strings left
     * out):
     * - huggingface.co/.../resolve/<rev>/encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx: 302 to
     *   https://us.aws.cdn.hf.co/xet-bridge-us/... (the Xet CDN, signed URL), then 200.
     * - huggingface.co/.../resolve/<rev>/tokens.txt: 307 to the relative /api/resolve-cache/models/... on
     *   huggingface.co, then 200.
     * - github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx and
     *   .../speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx: 302 to
     *   https://release-assets.githubusercontent.com/github-production-release-asset/..., then 200.
     * - dist.gocassini.com/models/files/<sha>/model.int8.onnx.zst: 200, no redirect.
     *
     * The Hugging Face CDN host depends on the region (for example cdn-lfs.hf.co, cas-bridge.xethub.hf.co or an
     * eu. host), and GitHub has used objects.githubusercontent.com before, so the subdomain entries are wider than
     * the trace.
     */
    val DOWNLOAD_HOSTS = listOf(
        "huggingface.co",
        "*.huggingface.co",
        "*.hf.co",
        "github.com",
        "*.githubusercontent.com",
        "dist.gocassini.com",
    )

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
