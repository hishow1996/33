package com.hishow.terminal33

import kotlin.math.max

/** Small VT-style screen buffer. It keeps cursor movement and SGR state separate from UI. */
class TerminalScreen(private val columns: Int = 120, private val rows: Int = 40) {
    data class Cell(val char: Char = ' ', val style: Int = 0)

    private val cells = Array(rows) { Array(columns) { Cell() } }
    private var cursorX = 0
    private var cursorY = 0
    private var style = 0
    private var esc = StringBuilder()

    fun feed(input: String) {
        for (ch in input) consume(ch)
    }

    private fun consume(ch: Char) {
        if (esc.isNotEmpty() || ch == '\u001b') {
            if (ch == '\u001b' && esc.isEmpty()) { esc.append(ch); return }
            esc.append(ch)
            if (esc.length == 2 && esc[1] !in "[]]" ) { esc.setLength(0); return }
            if (esc.length >= 2 && esc[1] == '[' && ch in '@'..'~') handleCsi(esc.substring(2, esc.length - 1), ch)
            else if (esc.length >= 2 && esc[1] == ']' && (ch == '\u0007' || ch == '\u001b')) esc.setLength(0)
            if ((esc.length > 64) || (esc.length >= 2 && esc[1] != '[' && esc[1] != ']')) esc.setLength(0)
            return
        }
        when (ch) {
            '\r' -> cursorX = 0
            '\n' -> newline()
            '\b' -> cursorX = max(0, cursorX - 1)
            '\t' -> cursorX = ((cursorX / 8) + 1) * 8
            '\u0007', '\u0000' -> Unit
            else -> put(ch)
        }
    }

    private fun put(ch: Char) {
        if (cursorX >= columns) newline()
        cells[cursorY][cursorX] = Cell(ch, style)
        cursorX++
        if (cursorX >= columns) newline()
    }

    private fun newline() {
        cursorX = 0
        cursorY++
        if (cursorY >= rows) {
            for (y in 1 until rows) cells[y - 1] = cells[y].copyOf()
            cells[rows - 1] = Array(columns) { Cell() }
            cursorY = rows - 1
        }
    }

    private fun handleCsi(params: String, command: Char) {
        val clean = params.removePrefix("?")
        val p = clean.split(';').mapNotNull { it.takeIf(String::isNotEmpty)?.toIntOrNull() }
        when (command) {
            'm' -> if (p.isEmpty()) style = 0 else p.forEach { style = if (it == 0) 0 else it }
            'H', 'f' -> { cursorY = ((p.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1); cursorX = ((p.getOrNull(1) ?: 1) - 1).coerceIn(0, columns - 1) }
            'A' -> cursorY = (cursorY - (p.getOrNull(0) ?: 1)).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + (p.getOrNull(0) ?: 1)).coerceAtMost(rows - 1)
            'C' -> cursorX = (cursorX + (p.getOrNull(0) ?: 1)).coerceAtMost(columns - 1)
            'D' -> cursorX = (cursorX - (p.getOrNull(0) ?: 1)).coerceAtLeast(0)
            'G' -> cursorX = ((p.getOrNull(0) ?: 1) - 1).coerceIn(0, columns - 1)
            'J' -> if ((p.getOrNull(0) ?: 0) == 2 || (p.getOrNull(0) ?: 0) == 3) clear()
            'K' -> { val from = cursorX; for (x in from until columns) cells[cursorY][x] = Cell() }
            'h', 'l' -> Unit
        }
    }

    fun clear() { for (row in cells) java.util.Arrays.fill(row, Cell()); cursorX = 0; cursorY = 0 }
    fun snapshot(): Array<Array<Cell>> = cells.map { it.copyOf() }.toTypedArray()
}
