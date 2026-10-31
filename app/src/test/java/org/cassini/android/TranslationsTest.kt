package org.cassini.android

import android.content.Context
import android.content.res.Configuration
import android.icu.text.PluralRules
import android.icu.util.ULocale
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The six app languages of coordination decisions D50 and D50.1: English (the source, `values`), Italian, French,
 * Spanish, Brazilian and European Portuguese. The checks read the string files of :app and of :ui (which :app
 * merges) as written, so a missing translation is seen even where Android would fall back to English:
 *
 * - every translatable string and plural of `values` is in each language, and no language has one more;
 * - each translation has the format arguments of the English text (a plural item may leave one out, as "one"
 *   items do with the number);
 * - each plural has every CLDR category of its language (ICU's rules, as the device uses them);
 * - "Cassini talk" and "Cassini" stay as they are (D45, D50 point 4).
 *
 * The phone language picks the files as D50.1 says: a bare `pt` gets pt-BR, other Portuguese regions pt-PT, a
 * regional variant its base language, and any other language English.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun everyStringIsInEveryLanguage() {
        for (module in MODULES) {
            val source = module.read(SOURCE)
            for (language in LANGUAGES) {
                val translated = module.read(language.dir)
                val where = "${module.name} ${language.dir}"
                assertEquals("Missing in $where", emptySet<String>(), source.keys - translated.keys)
                assertEquals("Not in English: $where", emptySet<String>(), translated.keys - source.keys)
            }
        }
    }

    @Test fun translationsKeepTheFormatArguments() {
        val wrong = MODULES.flatMap { module ->
            val source = module.read(SOURCE)
            LANGUAGES.flatMap { language ->
                module.read(language.dir).values.flatMap { entry ->
                    val english = arguments(source.getValue(entry.name).other)
                    entry.texts.mapNotNull { (quantity, text) ->
                        val found = arguments(text)
                        val ok = if (quantity == OTHER) found == english else english.containsAll(found)
                        if (ok) null else "${language.dir} ${entry.name} $quantity: $found, English $english"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun pluralsHaveTheCategoriesOfTheirLanguage() {
        val wrong = MODULES.flatMap { module ->
            (LANGUAGES + English).flatMap { language ->
                val categories = PluralRules.forLocale(ULocale.forLanguageTag(language.tag)).keywords
                module.read(language.dir).values.filter { it.plural }.mapNotNull { entry ->
                    val quantities = entry.texts.keys
                    if (quantities == categories) null else "${language.dir} ${entry.name}: $quantities, CLDR $categories"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun theNamesAreNeverTranslated() {
        val wrong = MODULES.flatMap { module ->
            val source = module.read(SOURCE)
            LANGUAGES.flatMap { language ->
                module.read(language.dir).values.mapNotNull { entry ->
                    val english = source.getValue(entry.name).other
                    val names = NAME.findAll(english).map { it.value }.toList()
                    val kept = entry.texts.values.all { NAME.findAll(it).map { m -> m.value }.toList() == names }
                    if (kept) null else "${language.dir} ${entry.name}: ${entry.texts.values}"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun thePhoneLanguagePicksTheFiles() {
        val ui = MODULES.single { it.name == "ui" }
        val brazil = ui.read("values-pt-rBR")
        val portugal = ui.read("values-pt-rPT")
        // A string that differs between the two Portuguese files tells them apart; one without escapes or quotes
        // reads the same from the file and from the resources.
        val plain = { text: String -> '\\' !in text && '"' !in text }
        val name = brazil.keys.first {
            val (br, pt) = brazil.getValue(it).other to portugal.getValue(it).other
            br != pt && plain(br) && plain(pt)
        }
        val expected = mapOf(
            "pt" to "values-pt-rBR",
            "pt-BR" to "values-pt-rBR",
            "pt-PT" to "values-pt-rPT",
            "pt-AO" to "values-pt-rPT",
            "pt-MZ" to "values-pt-rPT",
            "es-MX" to "values-es",
            "fr-CA" to "values-fr",
            "it-CH" to "values-it",
            "de-DE" to SOURCE,
        )
        for ((tag, dir) in expected) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val resources = context.createConfigurationContext(configuration).resources
            val id = resources.getIdentifier(name, "string", context.packageName)
            assertEquals("$tag reads $dir", ui.read(dir).getValue(name).other, resources.getString(id))
        }
    }

    @Test fun localesConfigListsTheSixLanguages() {
        val config = parse(File("src/main/res/xml/locales_config.xml"))
        val locales = config.getElementsByTagName("locale").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
        }
        assertEquals(listOf(English.tag) + LANGUAGES.map { it.tag }, locales)
    }

    /** A string or plural as written in a file; [texts] maps "other" (a string's only text) and the quantities. */
    private data class Entry(val name: String, val plural: Boolean, val texts: Map<String, String>) {
        val other: String get() = texts.getValue(OTHER)
    }

    private data class Language(val dir: String, val tag: String)

    private class Module(val name: String, val res: File) {
        private val cache = mutableMapOf<String, Map<String, Entry>>()

        fun read(dir: String): Map<String, Entry> = cache.getOrPut(dir) {
            val file = File(res, "$dir/strings.xml")
            assertTrue("$file is missing", file.isFile)
            entries(parse(file))
        }
    }

    private companion object {
        const val SOURCE = "values"
        const val OTHER = "other"
        val English = Language(SOURCE, "en")
        val LANGUAGES = listOf(
            Language("values-it", "it"),
            Language("values-fr", "fr"),
            Language("values-es", "es"),
            Language("values-pt-rBR", "pt-BR"),
            Language("values-pt-rPT", "pt-PT"),
        )

        // The unit tests of :app run in its project directory; :ui is its sibling.
        val MODULES = listOf(Module("app", File("src/main/res")), Module("ui", File("../ui/src/main/res")))

        val ARGUMENT = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?([a-zA-Z%])""")
        val NAME = Regex("""Cassini( talk)?""")

        /** The format arguments of [text] as "1\$s"-style keys, without "%%". */
        fun arguments(text: String): Set<String> = ARGUMENT.findAll(text)
            .filter { it.groupValues[3] != "%" }
            .mapIndexed { index, match -> (match.groupValues[1].ifEmpty { "${index + 1}$" }) + match.groupValues[3] }
            .toSet()

        fun parse(file: File): Element =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

        /** The translatable strings and plurals of a resources file, by name. */
        fun entries(root: Element): Map<String, Entry> {
            val children = root.childNodes
            return (0 until children.length)
                .mapNotNull { children.item(it) as? Element }
                .filter { it.getAttribute("translatable") != "false" && it.tagName in setOf("string", "plurals") }
                .associate { element -> element.getAttribute("name") to entry(element) }
        }

        fun entry(element: Element): Entry {
            val name = element.getAttribute("name")
            if (element.tagName == "string") {
                return Entry(name, plural = false, texts = mapOf(OTHER to element.textContent))
            }
            val items = element.getElementsByTagName("item")
            val texts = (0 until items.length).associate { index ->
                val item = items.item(index) as Element
                item.getAttribute("quantity") to item.textContent
            }
            return Entry(name, plural = true, texts = texts)
        }
    }
}
