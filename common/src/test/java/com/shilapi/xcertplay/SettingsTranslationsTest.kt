package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Lint does not check this library module's translations, so Settings copy is guarded here.
class SettingsTranslationsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test fun everySettingsStringIsTranslatedWithTheSamePlaceholders() {
        val base = strings(File(res, "values")).filterKeys { it.startsWith("settings_") }
        val locales = res.listFiles { file -> file.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?")) }!!
        assertTrue("missing the shipped language folder $SHIPPED", locales.any { it.name == SHIPPED })
        for (locale in locales) {
            val translated = strings(locale)
            // LYNK&CO ships English and Simplified Chinese. The other folders still translate the
            // older upstream screens, and a settings key they miss falls back to English, so only
            // the shipped language has to be complete — every placeholder must still line up.
            if (locale.name == SHIPPED) {
                assertEquals(locale.name, emptySet<String>(), base.keys - translated.keys)
            }
            for ((name, text) in base) {
                val value = translated[name] ?: continue
                assertEquals("${locale.name}/$name", placeholders(text), placeholders(value))
            }
        }
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSet()

    private fun strings(folder: File): Map<String, String> = folder.listFiles { file -> file.extension == "xml" }!!
        .flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }.toMap()

    private companion object {
        /** The only locale where the settings copy must be complete. */
        const val SHIPPED = "values-zh-rCN"
    }
}
