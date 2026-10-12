package org.cassini.core.export

import org.cassini.core.format.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal fun w(speaker: String, startMs: Long, endMs: Long, text: String) = Word(speaker, startMs, endMs, text)

/** Words of [speaker] from [text], each [stepMs] apart and lasting [stepMs] - 100. */
internal fun said(speaker: String, startMs: Long, text: String, stepMs: Long = 400): List<Word> =
    text.split(' ').mapIndexed { i, word -> w(speaker, startMs + i * stepMs, startMs + (i + 1) * stepMs - 100, word) }

internal fun transcript(
    words: List<Word>,
    labels: Map<String, String> = mapOf("spk_0" to "Paolo", "spk_1" to "Speaker 2", "spk_2" to "Élodie"),
    durationMs: Long = 23 * 60_000L + 50_000,
    title: String = "Chiamata con il fornitore",
) = ExportTranscript(title, durationMs, words, labels)

internal fun resource(name: String): String =
    checkNotNull(ExportFixtures::class.java.getResourceAsStream("/export/$name")) { "Missing $name" }
        .use { it.readBytes().toString(Charsets.UTF_8) }

/** UTF-8 without BOM, `\n` line endings and exactly one trailing `\n`. */
internal fun assertExportShape(text: String) {
    assertFalse("BOM", text.startsWith("﻿"))
    assertFalse("CR", text.contains('\r'))
    assertTrue("trailing newline", text.endsWith("\n"))
    assertFalse("two trailing newlines", text.endsWith("\n\n"))
}

/** [actual] equals [expected] and has the shape every export has. */
internal fun assertExport(expected: String, actual: String) {
    assertEquals(expected, actual)
    assertExportShape(actual)
}

private object ExportFixtures
