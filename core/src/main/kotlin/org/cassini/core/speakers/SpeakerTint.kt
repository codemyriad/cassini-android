package org.cassini.core.speakers

/**
 * The colour of a speaker's label and avatar (features.md section 0). A placeholder speaker is always [AMBER] ("not
 * named yet"). Named speakers cycle [BLUE], [TEAL], [VIOLET] in order of their first turn; placeholder speakers do not
 * take a slot. The `:ui` theme maps each value to its tone.
 */
enum class SpeakerTint {
    BLUE,
    TEAL,
    VIOLET,
    AMBER,
    ;

    companion object {
        /** The cycle of named speakers. */
        val NAMED = listOf(BLUE, TEAL, VIOLET)

        /** The tint of the named speaker at [namedIndex] (from 0) among the named speakers only. */
        fun named(namedIndex: Int): SpeakerTint = NAMED[Math.floorMod(namedIndex, NAMED.size)]

        /**
         * The tint of each speaker in [order] (speaker ids in order of their first turn in the shown variant, as
         * `SpeakerStats` ordinals give them). [isPlaceholder] tells a speaker without a name.
         */
        fun assign(order: List<String>, isPlaceholder: (String) -> Boolean): Map<String, SpeakerTint> {
            val result = LinkedHashMap<String, SpeakerTint>()
            var named = 0
            for (id in order.distinct()) result[id] = if (isPlaceholder(id)) AMBER else named(named++)
            return result
        }

        /** [assign] with display [labels]; a missing label is a placeholder ([DefaultLabels.isDefault]). */
        fun assign(order: List<String>, labels: Map<String, String>): Map<String, SpeakerTint> =
            assign(order) { id -> DefaultLabels.isDefault(id, labels[id] ?: id) }
    }
}
