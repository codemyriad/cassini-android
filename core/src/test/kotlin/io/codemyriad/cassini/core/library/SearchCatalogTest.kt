package io.codemyriad.cassini.core.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

class SearchCatalogTest {
    @Test fun vendoredFilterCopyMatchesItsCassiniUxPin() {
        val pin = JSONObject(resource("UPSTREAM.json"))
        assertEquals("https://github.com/codemyriad/cassini-ux", pin.getString("repository"))
        val bytes = requireNotNull(javaClass.getResourceAsStream("/search/strings.json")).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(pin.getJSONObject("files").getJSONObject("strings.json").getString("sha256"), digest)
    }

    @Test fun filterResourcesUseTheApprovedCopyInAllSixLanguages() {
        val entries = JSONObject(resource("strings.json")).getJSONArray("entries")
        val languages = mapOf(
            "values" to "en",
            "values-it" to "it",
            "values-fr" to "fr",
            "values-es" to "es",
            "values-pt-rBR" to "pt-BR",
            "values-pt-rPT" to "pt-PT",
        )
        for ((directory, language) in languages) {
            val file = File(System.getProperty("cassini.appResources"), "$directory/strings.xml")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = doc.getElementsByTagName("string")
            val strings = (0 until elements.length).associate { index ->
                val element = elements.item(index) as org.w3c.dom.Element
                element.getAttribute("name") to element.textContent.replace("\\'", "'")
            }
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                val expected = if (language == "en") entry.getString("en") else {
                    entry.getJSONObject("l10n").getJSONObject(language).getString("text")
                }
                assertEquals("$language/$id", expected, strings["ui_" + id.replace('.', '_')])
            }
        }
    }

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/search/$name")).readText()
}
