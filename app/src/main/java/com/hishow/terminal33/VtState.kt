package com.hishow.terminal33

/** Stateful ANSI/VT parser for the terminal UI layer. */
class VtState {
    data class Cell(val ch: Char = ' ', val sgr: Int = 0)

    var columns: Int = 120; private set
    var rows: Int = 40; private set
    var cursorX = 0; private set
    var cursorY = 0; private set
    var savedX = 0; private set
    var savedY = 0; private set
    var alternateScreen = false; private set
    var sgr = 0; private set

    private var normal = grid()
    private var alternate = grid()
    private var esc = StringBuilder()

    private fun grid() = Array(rows) { Array(columns) { Cell() } }
    private fun active() = if (alternateScreen) alternate else normal

    fun resize(newColumns: Int, newRows: Int) {
        val c = newColumns.coerceIn(20, 300)
        val r = newRows.coerceIn(5, 200)
        if (c == columns && r == rows) return
        columns = c; rows = r
        normal = resizeGrid(normal, c, r)
        alternate = resizeGrid(alternate, c, r)
        cursorX = cursorX.coerceIn(0, c - 1); cursorY = cursorY.coerceIn(0, r - 1)
    }

    private fun resizeGrid(old: Array<Array<Cell>>, c: Int, r: Int): Array<Array<Cell>> {
        val n = Array(r) { Array(c) { Cell() } }
        for (y in 0 until minOf(r, old.size)) for (x in 0 until minOf(c, old[y].size)) n[y][x] = old[y][x]
        return n
    }

    fun feed(input: String) { input.forEach(::consume) }

    private fun consume(ch: Char) {
        if (esc.isNotEmpty() || ch == '\u001b') {
            if (ch == '\u001b' && esc.isEmpty()) { esc.append(ch); return }
            esc.append(ch)
            if (esc.length == 2 && esc[1] !in "[]]7") { esc.setLength(0); return }
            if (esc.length >= 2 && esc[1] == '[' && ch in '@'..'~') { handleCsi(esc.substring(2, esc.length - 1), ch); esc.setLength(0); return }
            if (esc.length >= 2 && esc[1] == ']' && ch == '\u0007') { esc.setLength(0); return }
            if (esc.length >= 2 && esc[1] == '7') { savedX = cursorX; savedY = cursorY; esc.setLength(0); return }
            if (esc.length >= 2 && esc[1] == '8') { cursorX = savedX; cursorY = savedY; esc.setLength(0); return }
            if (esc.length > 96) esc.setLength(0)
            return
        }
        when (ch) {
            '\r' -> cursorX = 0
            '\n' -> newline()
            '\b' -> cursorX = (cursorX - 1).coerceAtLeast(0)
            '\t' -> cursorX = (((cursorX / 8) + 1) * 8).coerceAtMost(columns - 1)
            '\u0007', '\u0000' -> Unit
            else -> put(ch)
        }
    }

    private fun put(ch: Char) {
        val g = active()
        g[cursorY][cursorX] = Cell(ch, sgr)
        if (cursorX == columns - 1) newline() else cursorX++
    }

    private fun newline() {
        cursorX = 0
        if (cursorY == rows - 1) {
            val g = active()
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
            'h' -> if (privateMode && p.contains(1049)) enterAlternate()
            'l' -> if (privateMode && p.contains(1049)) leaveAlternate()
        }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) { sgr = 0; return }
        params.forEach { value -> if (value == 0) sgr = 0 else if (value in 30..37 || value in 90..97 || value in 40..47 || value in 100..107 || value in 1..2 || value == 4 || value == 7) sgr = value }
    }

    private fun eraseDisplay(mode: Int) { val g = active(); when (mode) { 2, 3 -> for (row in g) java.util.Arrays.fill(row, Cell()); 0 -> { for (x in cursorX until columns) g[cursorY][x] = Cell(); for (y in cursorY + 1 until rows) java.util.Arrays.fill(g[y], Cell()) }; 1 -> { for (y in 0 until cursorY) java.util.Arrays.fill(g[y], Cell()); for (x in 0..cursorX) g[cursorY][x] = Cell() } } }
    private fun eraseLine(mode: Int) { val g = active(); when (mode) { 2 -> java.util.Arrays.fill(g[cursorY], Cell()); 1 -> for (x in 0..cursorX) g[cursorY][x] = Cell(); else -> for (x in cursorX until columns) g[cursorY][x] = Cell() } }
    private fun deleteChars(count: Int) { val g = active()[cursorY]; val n = count.coerceAtMost(columns - cursorX); for (x in cursorX until columns - n) g[x] = g[x + n]; for (x in columns - n until columns) g[x] = Cell() }
    private fun insertChars(count: Int) { val g = active()[cursorY]; val n = count.coerceAtMost(columns - cursorX); for (x in columns - 1 downTo cursorX + n) g[x] = g[x - n]; for (x in cursorX until cursorX + n) g[x] = Cell() }

    private fun enterAlternate() { if (!alternateScreen) { alternate = grid(); alternateScreen = true; cursorX = 0; cursorY = 0 } }
    private fun leaveAlternate() { if (alternateScreen) { alternateScreen = false; cursorX = 0; cursorY = 0 } }

    fun snapshot(): Array<Array<Cell>> = active().map { it.copyOf() }.toTypedArray()
}
