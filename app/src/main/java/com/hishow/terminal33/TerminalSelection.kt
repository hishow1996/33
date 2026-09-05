package com.hishow.terminal33

/** A normalized inclusive cell range used by terminal text selection. */
data class TerminalCellPosition(val row: Int, val column: Int) : Comparable<TerminalCellPosition> {
    override fun compareTo(other: TerminalCellPosition): Int =
        compareValuesBy(this, other, TerminalCellPosition::row, TerminalCellPosition::column)
}

data class TerminalSelection(val start: TerminalCellPosition, val end: TerminalCellPosition) {
    val first: TerminalCellPosition get() = if (start <= end) start else end
    val last: TerminalCellPosition get() = if (start <= end) end else start

    fun contains(row: Int, column: Int): Boolean {
        val p = TerminalCellPosition(row, column)
        return p >= first && p <= last
    }
}
