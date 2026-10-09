package org.cassini.core.export

/** File names for saved copies and exports (features.md section 9). */
object ExportNames {
    /** The longest name before the extension, in UTF-16 units. */
    const val MAX_LENGTH = 100

    /** The name used when the title leaves nothing. */
    const val FALLBACK = "Cassini note"

    private const val FORBIDDEN = "/\\:*?\"<>|"

    /**
     * A file name for [title] with extension [ext] (without the dot): `/ \ : * ? " < > |` and control characters
     * become `_`, whitespace runs become one space, dots and spaces are trimmed at both ends, and the name is cut to
     * [MAX_LENGTH] UTF-16 units, never inside a surrogate pair (then trimmed again). A name with nothing left is
     * [FALLBACK].
     */
    fun file(title: String, ext: String): String {
        val replaced = StringBuilder(title.length)
        var space = false
        for (c in title) {
            when {
                c in FORBIDDEN || Character.isISOControl(c) -> {
                    replaced.append('_')
                    space = false
                }
                Character.isWhitespace(c) || Character.isSpaceChar(c) -> {
                    if (!space) replaced.append(' ')
                    space = true
                }
                else -> {
                    replaced.append(c)
                    space = false
                }
            }
        }
        var name = trim(replaced.toString())
        if (name.length > MAX_LENGTH) {
            val end = if (Character.isHighSurrogate(name[MAX_LENGTH - 1])) MAX_LENGTH - 1 else MAX_LENGTH
            name = trim(name.substring(0, end))
        }
        return name.ifEmpty { FALLBACK } + "." + ext
    }

    private fun trim(name: String): String = name.trim { it == '.' || it == ' ' }
}
