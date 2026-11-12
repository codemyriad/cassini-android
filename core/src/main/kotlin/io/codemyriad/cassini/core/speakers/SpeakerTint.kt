package io.codemyriad.cassini.core.speakers

/** Six slots fixed at first speech; unnamed speakers hide their slot behind stone. */
enum class SpeakerTint {
    ONE, TWO, THREE, FOUR, FIVE, SIX, UNNAMED;

    companion object {
        /** The cycle of named speakers. */
        val NAMED = listOf(ONE, TWO, THREE, FOUR, FIVE, SIX)

        /** The tint of the named speaker at [namedIndex] (from 0) in the meeting (including speakers not named yet). */
        fun named(namedIndex: Int): SpeakerTint = NAMED[Math.floorMod(namedIndex, NAMED.size)]

        /**
         * The tint of each speaker in [order] (speaker ids in order of their first turn in the shown variant, as
         * `SpeakerStats` ordinals give them). [isPlaceholder] tells a speaker without a name.
         */
        fun assign(
            order: List<String>,
            colourIndices: Map<String, Int> = emptyMap(),
            isPlaceholder: (String) -> Boolean,
        ): Map<String, SpeakerTint> {
            val result = LinkedHashMap<String, SpeakerTint>()
            for ((index, id) in order.distinct().withIndex()) {
                result[id] = if (isPlaceholder(id)) UNNAMED else named(colourIndices[id] ?: index)
            }
            return result
        }

        /** [assign] with display [labels]; a missing label is a placeholder ([DefaultLabels.isDefault]). */
        fun assign(order: List<String>, labels: Map<String, String>): Map<String, SpeakerTint> =
            assign(order) { id -> DefaultLabels.isDefault(id, labels[id] ?: id) }
    }
}
