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
 * (`ModelStore`, `DiarizationModels`, `VoiceprintModel`, `ModelBundle`), except the speech model: Parakeet Ultra
 * replaced Parakeet TDT 0.6B v3 in its own directory. Changing a pin, a directory or a marker value makes installed
 * models look missing or, worse, look ready when they are not. The sizes are pinned data.
 */
@Suppress("MagicNumber")
object ModelCatalog {
    /**
     * Parakeet Ultra INT8 on Hugging Face: tpmaerk's sherpa-onnx `nemo_transducer` export of moondream/parakeet-ultra
     * (revision 73175eb7), a post-train of NVIDIA Parakeet TDT 0.6B v3. The export changes only the format; the token
     * table is the one of v3. Its marker `parakeet-ultra/verified` holds this revision.
     */
    const val PARAKEET_REVISION = "b86784a3a5356cbd126f710b61cd90c5fe5898f1"
    const val PARAKEET_REPOSITORY = "tpmaerk/parakeet-ultra-sherpa-onnx-int8"
    const val PARAKEET_PRECISION = "INT8"

    /** The model id that a document's provenance names (`<id> <precision>`), and the name the app shows for it. */
    const val PARAKEET_MODEL_ID = "moondream/parakeet-ultra"
    const val PARAKEET_DISPLAY_NAME = "Parakeet Ultra"

    /**
     * Parakeet TDT 0.6B v3 INT8 (csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8), the speech model of 0.1.0 to
     * 0.2.0. This build does not install it. Documents and checkpoints made with it keep its id and revision.
     */
    const val PARAKEET_V3_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    const val PARAKEET_V3_MODEL_ID = "nvidia/parakeet-tdt-0.6b-v3"
    const val PARAKEET_V3_DISPLAY_NAME = "Parakeet TDT 0.6B v3"

    /**
     * The name the app shows for each speech model id that a document's provenance can name: this build's model and
     * the earlier ones, so a document keeps its true model name.
     */
    val SPEECH_MODEL_NAMES = mapOf(
        PARAKEET_MODEL_ID to PARAKEET_DISPLAY_NAME,
        PARAKEET_V3_MODEL_ID to PARAKEET_V3_DISPLAY_NAME,
    )

    private fun huggingFace(name: String) = "https://huggingface.co/$PARAKEET_REPOSITORY/resolve/$PARAKEET_REVISION/$name"

    val parakeetArtifacts = listOf(
        Artifact(
            "encoder.int8.onnx",
            652282347,
            "f4d06712d32431b1718eceba2153c314ac4425e8fe708a3534f5e56c5ad53697",
            huggingFace("encoder.int8.onnx"),
        ),
        Artifact(
            "decoder.int8.onnx",
            11845275,
            "94373c6405c8149d1c9d1e9862514e0e3e88dab1c7de18fac9d5df9c6ba5efdd",
            huggingFace("decoder.int8.onnx"),
        ),
        Artifact(
            "joiner.int8.onnx",
            6355277,
            "8a71aaccfdba3d451775507a889afd2257d2e754463fd81095a8c8f290f924c2",
            huggingFace("joiner.int8.onnx"),
        ),
        Artifact(
            "tokens.txt",
            93939,
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            huggingFace("tokens.txt")
        ),
    )

    /**
     * A new directory, not `parakeet-v3/`: the files of v3 never look like Ultra files, and a `.part` file of v3 is
     * never resumed as an Ultra file.
     */
    val PARAKEET = ModelDescriptor("parakeet-ultra", parakeetArtifacts, "verified", PARAKEET_REVISION)

    /**
     * Model directories of earlier builds that this build does not read (`parakeet-v3/`, about 640 MiB). The app
     * deletes them at startup (`ModelRepository.removeRetired`). They stay reserved names at the top of `filesDir`.
     */
    val RETIRED_DIRECTORIES = listOf("parakeet-v3")

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
