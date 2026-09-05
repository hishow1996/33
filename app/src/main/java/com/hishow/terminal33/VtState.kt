package com.hishow.terminal33

/** Lightweight VT screen model with a bounded scrollback buffer for mobile terminal use. */
class VtState {
    data class Cell(val ch: Char = ' ', val fg: Int = -1, val bg: Int = -1, val bold: Boolean = false, val underline: Boolean = false, val reverse: Boolean = false)

    companion object { private const val MAX_SCROLLBACK = 2000 }

    var columns = 120; private set
    var rows = 40; private set
    var cursorX = 0; private set
    var cursorY = 0; private set
    var savedX = 0; private set
    var savedY = 0; private set
    var alternateScreen = false; private set
    var cursorVisible = true; private set
    var scrollOffset = 0; private set

    private var fg = -1; private var bg = -1; private var bold = false; private var underline = false; private var reverse = false
    private var normal = grid(); private var alternate = grid()
    private val scrollback = ArrayDeque<Array<Cell>>()
    private val esc = StringBuilder()

    private fun grid() = Array(rows) { Array(columns) { Cell() } }
    private fun active() = if (alternateScreen) alternate else normal

    fun resize(newColumns: Int, newRows: Int) {
        val c = newColumns.coerceIn(20, 300); val r = newRows.coerceIn(5, 200)
        if (c == columns && r == rows) return
        columns = c; rows = r
        normal = resizeGrid(normal, c, r); alternate = resizeGrid(alternate, c, r)
        while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
        cursorX = cursorX.coerceIn(0, c - 1); cursorY = cursorY.coerceIn(0, r - 1)
        scrollOffset = 0
    }

    private fun resizeGrid(old: Array<Array<Cell>>, c: Int, r: Int): Array<Array<Cell>> {
        val n = Array(r) { Array(c) { Cell() } }
        for (y in 0 until minOf(r, old.size)) for (x in 0 until minOf(c, old[y].size)) n[y][x] = old[y][x]
        return n
    }

    fun feed(input: String) {
        scrollOffset = 0
        input.forEach(::consume)
    }

    /** Positive delta reveals older output; negative delta moves back toward the live prompt. */
    fun scrollBy(delta: Int) {
        if (alternateScreen || scrollback.isEmpty()) return
        scrollOffset = (scrollOffset + delta).coerceIn(0, scrollback.size)
    }

    fun scrollToBottom() { scrollOffset = 0 }

    private fun consume(ch: Char) {
        if (esc.isNotEmpty() || ch == '\u001b') {
            if (ch == '\u001b' && esc.isEmpty()) { esc.append(ch); return }
            esc.append(ch)
            if (esc.length == 2) {
                when (esc[1]) {
                    '7' -> { savedX = cursorX; savedY = cursorY; esc.setLength(0); return }
                    '8' -> { cursorX = savedX.coerceIn(0, columns - 1); cursorY = savedY.coerceIn(0, rows - 1); esc.setLength(0); return }
                }
                if (esc[1] !in "[]") { esc.setLength(0); return }
            }
            if (esc.length >= 2 && esc[1] == '[' && ch in '@'..'~') {
                handleCsi(esc.substring(2, esc.length - 1), ch); esc.setLength(0); return
            }
            if (esc.length >= 2 && esc[1] == ']' && (ch == '\u0007' || ch == '\u001b')) { esc.setLength(0); return }
            if (esc.length > 128) esc.setLength(0)
            return
        }
        when (ch) {
            '\r' -> cursorX = 0
            '\n' -> newline()
            '\b' -> cursorX = (cursorX - 1).coerceAtLeast(0)
            '\t' -> cursorX = (((cursorX / 8) + 1) * 8).coerceAtMost(columns - 1)
            '\u0007', '\u0000' -> Unit
            else -> if (ch >= ' ') put(ch)
        }
    }

    private fun put(ch: Char) {
        active()[cursorY][cursorX] = Cell(ch, fg, bg, bold, underline, reverse)
        if (cursorX == columns - 1) newline() else cursorX++
    }

    private fun newline() {
        cursorX = 0
        if (cursorY == rows - 1) {
            val g = active()
            if (!alternateScreen) {
                scrollback.addLast(g[0].copyOf())
                if (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            }
            for (y in 1 until rows) g[y - 1] = g[y].copyOf()
            g[rows - 1] = Array(columns) { Cell() }
        } else cursorY++
    }

    private fun handleCsi(raw: String, command: Char) {
        val privateMode = raw.startsWith("?")
        val body = raw.removePrefix("?").removePrefix(">")
        val p = body.split(';').map { it.toIntOrNull() ?: 0 }
        val n = { i: Int, d: Int = 1 -> (p.getOrNull(i) ?: d).coerceAtLeast(1) }
        when (command) {
            'm' -> applySgr(p)
            'H', 'f' -> { cursorY = (n(0) - 1).coerceIn(0, rows - 1); cursorX = (n(1) - 1).coerceIn(0, columns - 1) }
            'A' -> cursorY = (cursorY - n(0)).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + n(0)).coerceAtMost(rows - 1)
            'C' -> cursorX = (cursorX + n(0)).coerceAtMost(columns - 1)
            'D' -> cursorX = (cursorX - n(0)).coerceAtLeast(0)
            'G', '`' -> cursorX = (n(0) - 1).coerceIn(0, columns - 1)
            'd' -> cursorY = (n(0) - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K' -> eraseLine(p.getOrNull(0) ?: 0)
            'P' -> deleteChars(n(0))
            '@' -> insertChars(n(0))
            's' -> { savedX = cursorX; savedY = cursorY }
            'u' -> { cursorX = savedX.coerceIn(0, columns - 1); cursorY = savedY.coerceIn(0, rows - 1) }
            'h' -> if (privateMode) applyMode(p, true)
            'l' -> if (privateMode) applyMode(p, false)
        }
    }

    private fun applyMode(params: List<Int>, enabled: Boolean) {
        params.forEach { when (it) {
            25 -> cursorVisible = enabled
            47, 1047, 1049 -> if (enabled) enterAlternate() else leaveAlternate()
        } }
    }

    private fun applySgr(values: List<Int>) {
        val p = if (values.isEmpty()) listOf(0) else values
        var i = 0
        while (i < p.size) {
            when (val v = p[i]) {
                0 -> { fg = -1; bg = -1; bold = false; underline = false; reverse = false }
                1 -> bold = true
                2, 22 -> bold = false
                4 -> underline = true
                24 -> underline = false
                7 -> reverse = true
                27 -> reverse = false
                39 -> fg = -1
                49 -> bg = -1
                in 30..37 -> fg = v - 30
                in 40..47 -> bg = v - 40
                in 90..97 -> fg = v - 90 + 8
                in 100..107 -> bg = v - 100 + 8
                38 -> if (i + 1 < p.size && p[i + 1] == 5 && i + 2 < p.size) { fg = p[i + 2].coerceIn(0, 255) + 16; i += 2 }
                    else if (i + 1 < p.size && p[i + 1] == 2 && i + 4 < p.size) { fg = rgb(p[i + 2], p[i + 3], p[i + 4]); i += 4 }
                48 -> if (i + 1 < p.size && p[i + 1] == 5 && i + 2 < p.size) { bg = p[i + 2].coerceIn(0, 255) + 16; i += 2 }
                    else if (i + 1 < p.size && p[i + 1] == 2 && i + 4 < p.size) { bg = rgb(p[i + 2], p[i + 3], p[i + 4]); i += 4 }
            }
            i++
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = 0x80000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun eraseDisplay(mode: Int) {
        val g = active()
        when (mode) {
            2, 3 -> for (row in g) java.util.Arrays.fill(row, Cell())
            0 -> { for (x in cursorX until columns) g[cursorY][x] = Cell(); for (y in cursorY + 1 until rows) java.util.Arrays.fill(g[y], Cell()) }
            1 -> { for (y in 0 until cursorY) java.util.Arrays.fill(g[y], Cell()); for (x in 0..cursorX) g[cursorY][x] = Cell() }
        }
    }

    private fun eraseLine(mode: Int) {
        val g = active()
        when (mode) {
            2 -> java.util.Arrays.fill(g[cursorY], Cell())
            1 -> for (x in 0..cursorX) g[cursorY][x] = Cell()
            else -> for (x in cursorX until columns) g[cursorY][x] = Cell()
        }
    }

    private fun deleteChars(count: Int) {
        val g = active()[cursorY]; val n = count.coerceAtMost(columns - cursorX)
        for (x in cursorX until columns - n) g[x] = g[x + n]
        for (x in columns - n until columns) g[x] = Cell()
    }

    private fun insertChars(count: Int) {
        val g = active()[cursorY]; val n = count.coerceAtMost(columns - cursorX)
        for (x in columns - 1 downTo cursorX + n) g[x] = g[x - n]
        for (x in cursorX until cursorX + n) g[x] = Cell()
    }

    private fun enterAlternate() { if (!alternateScreen) { alternate = grid(); alternateScreen = true; cursorX = 0; cursorY = 0; scrollOffset = 0 } }
    private fun leaveAlternate() { if (alternateScreen) { alternateScreen = false; cursorX = 0; cursorY = 0; scrollOffset = 0 } }

    /** Returns the current viewport, including scrollback when the user has moved away from the live prompt. */
    fun snapshot(): Array<Array<Cell>> {
        if (alternateScreen || scrollOffset == 0 || scrollback.isEmpty()) return active().map { it.copyOf() }.toTypedArray()
        val all = ArrayList<Array<Cell>>(scrollback.size + rows)
        all.addAll(scrollback)
        all.addAll(active().map { it.copyOf() })
        val end = all.size - scrollOffset
        val start = (end - rows).coerceAtLeast(0)
        val out = Array(rows) { Array(columns) { Cell() } }
        for (y in 0 until rows) {
            val source = start + y
            if (source in all.indices) out[y] = all[source].copyOf()
        }
        return out
    }
}
