package com.modulai.core.code

enum class DiffKind { SAME, ADDED, REMOVED }

/** One line of a diff; line numbers are 1-based and null on the side the line is absent from. */
data class DiffLine(val kind: DiffKind, val text: String, val oldLine: Int?, val newLine: Int?)

/** A run of changes with some unchanged lines around it. */
data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<DiffLine>)

data class DiffStats(val added: Int, val removed: Int)

fun List<Hunk>.stats() = DiffStats(
    sumOf { h -> h.lines.count { it.kind == DiffKind.ADDED } },
    sumOf { h -> h.lines.count { it.kind == DiffKind.REMOVED } },
)

/** Splits into lines without inventing an empty last line for text that ends with a newline. */
fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.replace("\r\n", "\n").split('\n')
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

/** Line-based diff of two texts, grouped into hunks with [context] unchanged lines around each change. */
fun diffLines(old: String, new: String, context: Int = 3): List<Hunk> {
    val lines = diffAll(splitLines(old), splitLines(new))
    val changed = lines.indices.filter { lines[it].kind != DiffKind.SAME }
    if (changed.isEmpty()) return emptyList()

    val hunks = ArrayList<Hunk>()
    var from = changed.first()
    var to = from
    fun flush() {
        val slice = lines.subList(maxOf(0, from - context), minOf(lines.size, to + context + 1))
        val oldLines = slice.mapNotNull { it.oldLine }
        val newLines = slice.mapNotNull { it.newLine }
        // A side without lines starts after the line preceding the hunk, as in unified diffs.
        val before = lines.subList(0, maxOf(0, from - context))
        hunks += Hunk(
            oldLines.firstOrNull() ?: before.count { it.oldLine != null },
            oldLines.size,
            newLines.firstOrNull() ?: before.count { it.newLine != null },
            newLines.size,
            slice.toList(),
        )
    }
    for (i in changed.drop(1)) {
        // Changes whose context would touch are shown as one hunk.
        if (i - to > 2 * context) {
            flush()
            from = i
        }
        to = i
    }
    flush()
    return hunks
}

private fun diffAll(a: List<String>, b: List<String>): List<DiffLine> {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

    val midA = a.subList(prefix, a.size - suffix)
    val midB = b.subList(prefix, b.size - suffix)
    val out = ArrayList<DiffLine>(a.size + b.size)
    var x = 0
    var y = 0
    fun same() = out.add(DiffLine(DiffKind.SAME, a[x], ++x, ++y))
    fun removed() = out.add(DiffLine(DiffKind.REMOVED, a[x], ++x, null))
    fun added() = out.add(DiffLine(DiffKind.ADDED, b[y], null, ++y))

    repeat(prefix) { same() }
    val script = myers(midA, midB)
    if (script == null) {
        // Too different to be worth aligning: show the middle as replaced wholesale.
        repeat(midA.size) { removed() }
        repeat(midB.size) { added() }
    } else {
        for (kind in script) when (kind) {
            DiffKind.SAME -> same()
            DiffKind.REMOVED -> removed()
            DiffKind.ADDED -> added()
        }
    }
    repeat(suffix) { same() }
    return out
}

/** Memory grows with the square of the edit distance, so very different texts are not aligned. */
private const val MAX_DISTANCE = 1500

/** Myers' O(ND) shortest edit script, or null when the distance exceeds [MAX_DISTANCE]. */
private fun myers(a: List<String>, b: List<String>): List<DiffKind>? {
    val n = a.size
    val m = b.size
    val limit = minOf(n + m, MAX_DISTANCE)
    val offset = limit + 1
    // v[k + offset] is the furthest x reached on diagonal k = x - y.
    val v = IntArray(2 * limit + 3)
    // trace[d] holds diagonals -d-1..d+1 as they were before step d.
    val trace = ArrayList<IntArray>()

    for (d in 0..limit) {
        trace += v.copyOfRange(offset - d - 1, offset + d + 2)
        var k = -d
        while (k <= d) {
            var x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) v[k + 1 + offset] else v[k - 1 + offset] + 1
            var y = x - k
            while (x < n && y < m && a[x] == b[y]) {
                x++
                y++
            }
            v[k + offset] = x
            if (x >= n && y >= m) return backtrack(trace, n, m)
            k += 2
        }
    }
    return null
}

private fun backtrack(trace: List<IntArray>, n: Int, m: Int): List<DiffKind> {
    val script = ArrayList<DiffKind>()
    var x = n
    var y = m
    for (d in trace.indices.reversed()) {
        val row = trace[d]
        fun at(k: Int) = row[k + d + 1]
        val k = x - y
        val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
        val prevX = at(prevK)
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) {
            script += DiffKind.SAME
            x--
            y--
        }
        if (d > 0) script += if (x == prevX) DiffKind.ADDED else DiffKind.REMOVED
        x = prevX
        y = prevY
    }
    return script.asReversed()
}
