package com.modulai.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPiecesTest {
    @Test
    fun charactersCutBetweenSegmentsAreJoined() {
        val bytes = "Привет, мир".toByteArray()
        for (cut in 1 until bytes.size) {
            val joiner = Utf8Joiner()
            val text = joiner.append(bytes.copyOfRange(0, cut)) + joiner.append(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", "Привет, мир", text)
        }
    }

    @Test
    fun fourByteCharactersSurviveAnyCut() {
        val bytes = "a😀b".toByteArray()
        for (cut in 1 until bytes.size) {
            val joiner = Utf8Joiner()
            val first = joiner.append(bytes.copyOfRange(0, cut))
            assertFalse("cut at $cut", '�' in first)
            assertEquals("a😀b", first + joiner.append(bytes.copyOfRange(cut, bytes.size)))
        }
    }

    private val tokenizer = ChatterboxTokenizer(
        """
        {
          "added_tokens": [
            {"id": 0, "content": "[STOP]"}, {"id": 1, "content": "[UNK]"}, {"id": 2, "content": "[SPACE]"},
            {"id": 20, "content": "[en]"}, {"id": 21, "content": "[ru]"}, {"id": 22, "content": "[laughter]"}
          ],
          "model": {
            "unk_token": "[UNK]",
            "vocab": {"[STOP]": 0, "[UNK]": 1, "[SPACE]": 2, "t": 3, "h": 4, "e": 5, "th": 6, "the": 7, "!": 8,
                      "и": 9, "̆": 10, "д": 11, "a": 12},
            "merges": ["t h", "th e"]
          }
        }
        """.trimIndent(),
    )

    @Test
    fun wordsAreMergedAndWrappedInControlTokens() {
        assertArrayEquals(
            longArrayOf(6563, 255, 20, 7, 2, 6, 12, 8, 0, 6561, 6561),
            tokenizer.encode("The tha!", "en"),
        )
    }

    @Test
    fun markersInsideTextStayWhole() {
        assertArrayEquals(
            longArrayOf(6563, 255, 20, 12, 2, 22, 2, 12, 0, 6561, 6561),
            tokenizer.encode("a [laughter] a", "en"),
        )
    }

    @Test
    fun accentedLettersAreDecomposedAndUnknownOnesMarked() {
        // "й" is fed to the model as "и" plus a combining breve; "z" is not in the vocabulary.
        assertArrayEquals(
            longArrayOf(6563, 255, 21, 11, 9, 10, 2, 1, 0, 6561, 6561),
            tokenizer.encode("Дй z", "ru"),
        )
    }

    @Test
    fun languagesAreThoseWithAMarker() {
        assertTrue(tokenizer.supports("ru"))
        assertFalse(tokenizer.supports("ja"))
    }
}
