package io.codemyriad.cassini.core.library

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
        assertEquals("\"delivery*\" \"caf*\"", fts("delivery caf"))
    }

    @Test fun quotesAndStarsAreDropped() {
        assertEquals("\"delivery*\" \"caf*\"", fts("\"delivery caf\""))
        assertEquals("\"deli*\"", fts("deli*"))
        assertEquals("\"deli*\"", fts("*deli**"))
        assertEquals("\"delivery*\"", fts("delivery \" *"))
    }

    @Test fun ftsOperatorsAreDropped() {
        assertEquals("\"start*\"", fts("^start"))
        assertEquals("\"body price*\"", fts("body:price"))
        assertEquals("\"a*\" \"b*\"", fts("(a) (b)"))
        assertEquals("\"price*\"", fts("-price"))
        assertEquals("\"del ivery*\"", fts("del*ivery"))
        assertNull(fts("-- : () ^ \"\" *"))
    }

    @Test fun innerPunctuationSplitsTokensAsTheTokenizerDoes() {
        // unicode61 indexes "e-mail" as the tokens "e" and "mail", so the phrase must have the same two tokens.
        assertEquals("\"e mail*\" \"follow*\" \"up*\"", fts("e-mail follow- -up"))
        assertEquals("\"follow up*\"", fts("follow-up"))
        assertEquals("\"it s*\"", fts("it's"))
        assertEquals("\"snake case*\"", fts("snake_case"))
    }

    @Test fun trailingPunctuationKeepsThePrefixMatch() {
        // "deliver,*" would end the phrase on a comma: the star would not follow a token and match only "deliver".
        assertEquals("\"deliver*\"", fts("deliver,"))
        assertEquals("\"caf*\"", fts("caf."))
        assertEquals("\"really*\"", fts("¿really?!"))
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
        assertEquals("\"café*\" \"DELIVERY*\"", fts("café DELIVERY"))
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
        // FTS4 matches nothing when one phrase has no token, so "delivery ," would find nothing.
        assertEquals("\"delivery*\"", fts("delivery , … ?!"))
        assertEquals("\"2026*\"", fts("2026"))
    }

    @Test fun highlightsInTheMiddle() {
        val parsed = SearchQuery.highlights("so the \u0002delivery\u0003 slips to \u0002café\u0003, but")
        assertEquals("so the delivery slips to café, but", parsed.text)
        assertEquals(listOf(7..14, 25..28), parsed.ranges)
        assertEquals("delivery", parsed.text.substring(parsed.ranges[0]))
        assertEquals("café", parsed.text.substring(parsed.ranges[1]))
        assertFalse(parsed.leadingEllipsis)
        assertFalse(parsed.trailingEllipsis)
    }

    @Test fun highlightsAtStartAndEnd() {
        val start = SearchQuery.highlights("\u0002Delivery\u0003 slips")
        assertEquals("Delivery slips", start.text)
        assertEquals(listOf(0..7), start.ranges)
        val end = SearchQuery.highlights("the \u0002delivery\u0003")
        assertEquals("the delivery", end.text)
        assertEquals(listOf(4..11), end.ranges)
        val whole = SearchQuery.highlights("\u0002delivery\u0003")
        assertEquals(listOf(0..7), whole.ranges)
    }

    @Test fun adjacentHighlightsStaySeparate() {
        val parsed = SearchQuery.highlights("\u0002del\u0003\u0002ivery\u0003 x")
        assertEquals("delivery x", parsed.text)
        assertEquals(listOf(0..2, 3..7), parsed.ranges)
    }

    @Test fun ellipsesBecomeFlags() {
        val parsed = SearchQuery.highlights("…first \u0002delivery\u0003 slips…")
        assertEquals("first delivery slips", parsed.text)
        assertEquals(listOf(6..13), parsed.ranges)
        assertTrue(parsed.leadingEllipsis)
        assertTrue(parsed.trailingEllipsis)
        val atEdges = SearchQuery.highlights("…\u0002delivery\u0003…")
        assertEquals(Highlighted("delivery", listOf(0..7), leadingEllipsis = true, trailingEllipsis = true), atEdges)
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
