package com.hishow.terminal33

/** Escape/control sequences used by the Android terminal input layer. */
object TerminalInput {
    const val CTRL_C = "\u0003"
    const val CTRL_D = "\u0004"
    const val CTRL_Z = "\u001A"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val ESC = "\u001B"
    const val BACKSPACE = "\u007F"

    fun arrowUp() = "\u001B[A"
    fun arrowDown() = "\u001B[B"
    fun arrowRight() = "\u001B[C"
    fun arrowLeft() = "\u001B[D"
    fun home() = "\u001B[H"
    fun end() = "\u001B[F"
    fun pageUp() = "\u001B[5~"
    fun pageDown() = "\u001B[6~"
    fun delete() = "\u001B[3~"

    fun functionKey(number: Int): String = when (number) {
        1 -> "\u001BOP"
        2 -> "\u001BOQ"
        3 -> "\u001BOR"
        4 -> "\u001BOS"
        5 -> "\u001B[15~"
        6 -> "\u001B[17~"
        7 -> "\u001B[18~"
        8 -> "\u001B[19~"
        9 -> "\u001B[20~"
        10 -> "\u001B[21~"
        11 -> "\u001B[23~"
        12 -> "\u001B[24~"
        else -> ""
    }
}
