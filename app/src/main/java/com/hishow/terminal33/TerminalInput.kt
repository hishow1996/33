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

    fun ctrl(letter: Char): String {
        val c = letter.uppercaseChar()
        return if (c in 'A'..'Z') (c.code - 'A'.code + 1).toChar().toString() else ""
    }

    fun alt(text: String): String = if (text.isEmpty()) "" else ESC + text

    /** Normalizes Android/Windows line endings without adding terminal control bytes. */
    fun normalizePaste(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /** Bracketed paste payload for applications which have enabled DECSET 2004. */
    fun bracketedPaste(text: String): String =
        "\u001B[200~" + normalizePaste(text) + "\u001B[201~"

    /** xterm-style modifier parameter: 2=Shift, 3=Alt, 4=Shift+Alt, 5=Ctrl, 6=Shift+Ctrl, 7=Alt+Ctrl, 8=Shift+Alt+Ctrl. */
    private fun modifier(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    /** Modified cursor keys, compatible with xterm/vim-style terminal applications. */
    fun modifiedArrow(direction: Char, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val suffix = when (direction) {
            'A' -> 'A'
            'B' -> 'B'
            'C' -> 'C'
            'D' -> 'D'
            else -> return ""
        }
        val mod = modifier(shift, alt, ctrl)
        return if (mod == 1) "\u001B[$suffix" else "\u001B[1;${mod}${suffix}"
    }

    fun arrowUp() = modifiedArrow('A')
    fun arrowDown() = modifiedArrow('B')
    fun arrowRight() = modifiedArrow('C')
    fun arrowLeft() = modifiedArrow('D')

    fun home() = "\u001B[H"
    fun end() = "\u001B[F"
    fun pageUp() = "\u001B[5~"
    fun pageDown() = "\u001B[6~"
    fun delete() = "\u001B[3~"

    /** Common Ctrl+arrow/Home/End forms used by readline, shells and editors. */
    fun ctrlArrow(direction: Char): String = modifiedArrow(direction, ctrl = true)
    fun altArrow(direction: Char): String = modifiedArrow(direction, alt = true)
    fun shiftArrow(direction: Char): String = modifiedArrow(direction, shift = true)

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
