package com.modulai.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiffTest {
    private fun render(hunks: List<Hunk>) = hunks.joinToString("\n") { h ->
        "@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@\n" + h.lines.joinToString("\n") {
            when (it.kind) {
                DiffKind.SAME -> " "
                DiffKind.ADDED -> "+"
                DiffKind.REMOVED -> "-"
            } + it.text
        }
    }

    /** Replays the diff over [old]; a correct diff of full context reproduces [new]. */
    private fun apply(old: String, new: String): List<String> {
        val hunks = diffLines(old, new, context = Int.MAX_VALUE / 4)
        if (hunks.isEmpty()) return splitLines(old)
        val lines = hunks.single().lines
        assertEquals(splitLines(old), lines.filter { it.kind != DiffKind.ADDED }.map { it.text })
        assertEquals(lines.mapNotNull { it.oldLine }, (1..splitLines(old).size).toList())
        assertEquals(lines.mapNotNull { it.newLine }, (1..splitLines(new).size).toList())
        return lines.filter { it.kind != DiffKind.REMOVED }.map { it.text }
    }

    @Test fun identicalTextsHaveNoHunks() {
        assertTrue(diffLines("a\nb\n", "a\nb\n").isEmpty())
        assertTrue(diffLines("", "").isEmpty())
        assertTrue("line endings alone are not a change", diffLines("a\r\nb", "a\nb\n").isEmpty())
    }

    @Test fun changedLineIsShownWithContext() {
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = old.replace("line 5", "LINE 5")
        assertEquals(
            """
            @@ -4,3 +4,3 @@
             line 4
            -line 5
            +LINE 5
             line 6
            """.trimIndent(),
            render(diffLines(old, new, context = 1)),
        )
        assertEquals(DiffStats(1, 1), diffLines(old, new).stats())
    }

    @Test fun distantChangesMakeSeparateHunksAndCloseOnesMerge() {
        val old = (1..30).joinToString("\n") { "line $it" }
        val far = old.replace("line 2\n", "").replace("line 28", "line 28\nextra")
        val hunks = diffLines(old, far, context = 2)
        assertEquals(2, hunks.size)
        assertEquals(listOf(1, 4, 1, 3), hunks[0].let { listOf(it.oldStart, it.oldCount, it.newStart, it.newCount) })
        assertEquals(listOf(27, 4, 26, 5), hunks[1].let { listOf(it.oldStart, it.oldCount, it.newStart, it.newCount) })

        val near = old.replace("line 10", "x").replace("line 13", "y")
        assertEquals(1, diffLines(old, near, context = 2).size)
    }

    @Test fun createdAndDeletedFiles() {
        val created = diffLines("", "a\nb\n").single()
        assertEquals(listOf(0, 0, 1, 2), listOf(created.oldStart, created.oldCount, created.newStart, created.newCount))
        assertTrue(created.lines.all { it.kind == DiffKind.ADDED })
        val deleted = diffLines("a\nb", "").single()
        assertEquals(listOf(1, 2, 0, 0), listOf(deleted.oldStart, deleted.oldCount, deleted.newStart, deleted.newCount))
    }

    @Test fun diffIsMinimal() {
        // The classic example from Myers' paper: the shortest script has five edits.
        val stats = diffLines("A\nB\nC\nA\nB\nB\nA", "C\nB\nA\nB\nA\nC").stats()
        assertEquals(5, stats.added + stats.removed)
    }

    @Test fun randomEditsRoundTrip() {
        val random = Random(7)
        repeat(300) {
            val old = List(random.nextInt(0, 40)) { "l${random.nextInt(6)}" }
            val new = old.toMutableList()
            repeat(random.nextInt(0, 8)) {
                when {
                    new.isEmpty() || random.nextBoolean() -> new.add(random.nextInt(new.size + 1), "n${random.nextInt(6)}")
                    else -> new.removeAt(random.nextInt(new.size))
                }
            }
            assertEquals(new, apply(old.joinToString("\n"), new.joinToString("\n")))
        }
    }

    @Test fun veryDifferentTextsFallBackToWholesaleReplacement() {
        val old = (1..2000).joinToString("\n") { "old $it" }
        val new = (1..2000).joinToString("\n") { "new $it" }
        assertEquals(splitLines(new), apply(old, new))
        assertEquals(DiffStats(2000, 2000), diffLines(old, new).stats())
    }
}
