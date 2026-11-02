package org.cassini.core.models

import java.io.File

/**
 * What a model download still has to install, with the sizes the Model download screen shows and the free space it
 * checks before the first byte.
 *
 * A part is missing when its primary model is not ready. A missing part brings every one of its models that is not
 * ready, extras included; an extra missing from a ready part is not in the plan (it is best effort).
 */
data class ModelPlan(val missing: List<Item>) {
    /** One missing [part] and the [models] of it still to install. */
    data class Item(val part: ModelPart, val models: List<ModelDescriptor>) {
        /** Bytes fetched over the network. */
        val downloadBytes: Long get() = models.sumOf { it.downloadBytes }

        /**
         * Bytes on disk at most while installing: the models install one after another, so every installed file plus
         * the largest single download being decompressed ([ModelDescriptor.installBytes]).
         */
        val installBytes: Long
            get() = models.sumOf { it.artifactBytes } + (models.maxOfOrNull { it.largestDownloadBytes } ?: 0L)

        /** The size shown next to the part: [downloadBytes] in whole MiB as [ModelCatalog.mebibytes] rounds it. */
        val mebibytes: Long get() = ModelCatalog.mebibytes(downloadBytes)
    }

    val parts: List<ModelPart> get() = missing.map { it.part }

    val isEmpty: Boolean get() = missing.isEmpty()

    /** Download bytes of each missing part. */
    val downloadBytes: Map<ModelPart, Long> get() = missing.associate { it.part to it.downloadBytes }

    /** Every byte the plan fetches over the network. */
    val totalBytes: Long get() = missing.sumOf { it.downloadBytes }

    /**
     * The total the screen shows: the sum of the rounded part sizes, so the numbers on screen add up (440 + 62 + 26 =
     * 528 for a fresh install; `mebibytes(totalBytes)` would round the same bytes to 530).
     */
    val totalMebibytes: Long get() = missing.sumOf { it.mebibytes }

    /**
     * Free space needed before the first byte: every file of the missing parts, plus the largest single download
     * (the parts install one after another, and each download is deleted once its file verified), plus
     * [ArtifactFetcher.SLACK_BYTES]; 0 when nothing is missing. Kept `.part` files are not subtracted;
     * [ArtifactFetcher] checks each file again with what it still has to fetch.
     */
    val requiredFreeBytes: Long
        get() {
            if (missing.isEmpty()) return 0
            val models = missing.flatMap { it.models }
            val largest = models.maxOfOrNull { it.largestDownloadBytes } ?: 0L
            return models.sumOf { it.artifactBytes } + largest + ArtifactFetcher.SLACK_BYTES
        }

    /**
     * [requiredFreeBytes] as the "Needs about 1230 MB free" figure, rounded to the nearest 10 like every size on the
     * screen (a fresh install needs about 1231 MiB and shows 1230; the space check itself compares bytes).
     */
    val requiredFreeMebibytes: Long get() = if (missing.isEmpty()) 0 else ModelCatalog.mebibytes(requiredFreeBytes)

    companion object {
        /** The plan for the models [ready] reports, in [ModelPart] order. */
        fun of(ready: (ModelDescriptor) -> Boolean): ModelPlan = ModelPlan(
            ModelPart.entries.filterNot { ready(it.primary) }.map { part -> Item(part, part.models.filterNot(ready)) },
        )

        /** The plan for the models installed under [filesDir]. */
        fun of(filesDir: File): ModelPlan = of { it.ready(filesDir) }
    }
}
