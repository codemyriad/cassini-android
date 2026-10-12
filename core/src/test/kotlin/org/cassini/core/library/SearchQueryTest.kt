package org.cassini.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    // A phrase: tokens of letters, digits and marks separated by single spaces, then the star inside the quotes.
    private val phrase = "\"[\\p{L}\\p{N}\\p{M}]+( [\\p{L}\\p{N}\\p{M}]+)*\\*\""
    private val query = Regex("$phrase( $phrase)*")

    private fun fts(input: String): String? = SearchQuery.fts(input)?.also { fts ->
        // Every phrase is "term*": the star inside the quotes, never the FTS5 form "term"*.
        assertTrue(fts, query.matches(fts))
        assertFalse(fts, fts.contains("\"*"))
    }

    @Test fun golden() {
        assertEquals("\"consegna*\" \"giov*\"", fts("consegna giov"))
    }

    @Test fun quotesAndStarsAreDropped() {
        assertEquals("\"consegna*\" \"giov*\"", fts("\"consegna giov\""))
        assertEquals("\"cons*\"", fts("cons*"))
        assertEquals("\"cons*\"", fts("*cons**"))
        assertEquals("\"consegna*\"", fts("consegna \" *"))
    }

    @Test fun ftsOperatorsAreDropped() {
        assertEquals("\"inizio*\"", fts("^inizio"))
        assertEquals("\"body prezzo*\"", fts("body:prezzo"))
        assertEquals("\"a*\" \"b*\"", fts("(a) (b)"))
        assertEquals("\"prezzo*\"", fts("-prezzo"))
        assertEquals("\"con segna*\"", fts("con*segna"))
        assertNull(fts("-- : () ^ \"\" *"))
    }

    @Test fun innerPunctuationSplitsTokensAsTheTokenizerDoes() {
        // unicode61 indexes "e-mail" as the tokens "e" and "mail", so the phrase must have the same two tokens.
        assertEquals("\"e mail*\" \"follow*\" \"up*\"", fts("e-mail follow- -up"))
        assertEquals("\"follow up*\"", fts("follow-up"))
        assertEquals("\"l ordine*\"", fts("l'ordine"))
        assertEquals("\"snake case*\"", fts("snake_case"))
    }

    @Test fun trailingPunctuationKeepsThePrefixMatch() {
        // "consegn,*" would end the phrase on a comma: the star would not follow a token and match only "consegn".
        assertEquals("\"consegn*\"", fts("consegn,"))
        assertEquals("\"giov*\"", fts("giov."))
        assertEquals("\"davvero*\"", fts("¿davvero?!"))
    }

    @Test fun combiningMarksStayInTheirToken() {
        assertEquals("\"café*\"", fts("café."))
    }

    @Test fun operatorWordsAreQuoted() {
        assertEquals("\"price*\" \"AND*\" \"stays*\"", fts("price AND stays"))
        assertEquals("\"a*\" \"OR*\" \"b*\" \"NOT*\" \"c*\" \"NEAR*\" \"d*\"", fts("a OR b NOT c NEAR d"))
        assertEquals("\"NEAR 3*\"", fts("NEAR/3"))
        assertEquals("\"a*\" \"OR*\" \"b*\"", fts("(a OR b)"))
    }

    @Test fun accentsAndCaseAreKept() {
        // The unicode61 tokenizer with remove_diacritics=1 folds them; the query keeps what was typed.
        assertEquals("\"giovedì*\" \"CONSEGNA*\"", fts("giovedì CONSEGNA"))
        assertEquals("\"Élodie*\"", fts("Élodie"))
    }

    @Test fun emptyAndBlankInputs() {
        assertNull(fts(""))
        assertNull(fts("   "))
        assertNull(fts("\t\n 　"))
    }

    @Test fun whitespaceOfEveryKindSplits() {
        assertEquals("\"a*\" \"b*\" \"c*\" \"d*\"", fts(" a\tb c\nd "))
    }

    @Test fun termsWithoutLettersOrDigitsAreDropped() {
        // FTS4 matches nothing when one phrase has no token, so "consegna ," would find nothing.
        assertEquals("\"consegna*\"", fts("consegna , … ?!"))
        assertEquals("\"2026*\"", fts("2026"))
    }

    @Test fun highlightsInTheMiddle() {
        val parsed = SearchQuery.highlights("quindi la \u0002consegna\u0003 slitta a \u0002giovedì\u0003, but")
        assertEquals("quindi la consegna slitta a giovedì, but", parsed.text)
        assertEquals(listOf(10..17, 28..34), parsed.ranges)
        assertEquals("consegna", parsed.text.substring(parsed.ranges[0]))
        assertEquals("giovedì", parsed.text.substring(parsed.ranges[1]))
        assertFalse(parsed.leadingEllipsis)
        assertFalse(parsed.trailingEllipsis)
    }

    @Test fun highlightsAtStartAndEnd() {
        val start = SearchQuery.highlights("\u0002Consegna\u0003 slitta")
        assertEquals("Consegna slitta", start.text)
        assertEquals(listOf(0..7), start.ranges)
        val end = SearchQuery.highlights("la \u0002consegna\u0003")
        assertEquals("la consegna", end.text)
        assertEquals(listOf(3..10), end.ranges)
        val whole = SearchQuery.highlights("\u0002consegna\u0003")
        assertEquals(listOf(0..7), whole.ranges)
    }

    @Test fun adjacentHighlightsStaySeparate() {
        val parsed = SearchQuery.highlights("\u0002con\u0003\u0002segna\u0003 x")
        assertEquals("consegna x", parsed.text)
        assertEquals(listOf(0..2, 3..7), parsed.ranges)
    }

    @Test fun ellipsesBecomeFlags() {
        val parsed = SearchQuery.highlights("…prima \u0002consegna\u0003 slitta…")
        assertEquals("prima consegna slitta", parsed.text)
        assertEquals(listOf(6..13), parsed.ranges)
        assertTrue(parsed.leadingEllipsis)
        assertTrue(parsed.trailingEllipsis)
        val atEdges = SearchQuery.highlights("…\u0002consegna\u0003…")
        assertEquals(Highlighted("consegna", listOf(0..7), leadingEllipsis = true, trailingEllipsis = true), atEdges)
        val onlyEllipsis = Highlighted("", emptyList(), leadingEllipsis = true, trailingEllipsis = false)
        assertEquals(onlyEllipsis, SearchQuery.highlights("…"))
    }

    @Test fun malformedMarkers() {
        val empty = Highlighted("", emptyList(), leadingEllipsis = false, trailingEllipsis = false)
        assertEquals(empty, SearchQuery.highlights(""))
        assertEquals(listOf(3..7), SearchQuery.highlights("la \u0002conse").ranges)
        assertEquals(emptyList<IntRange>(), SearchQuery.highlights("la\u0003 x\u0002\u0003 y").ranges)
        assertEquals("la x y", SearchQuery.highlights("la\u0003 x\u0002\u0003 y").text)
        assertEquals(emptyList<IntRange>(), SearchQuery.highlights("plain text").ranges)
    }
}
