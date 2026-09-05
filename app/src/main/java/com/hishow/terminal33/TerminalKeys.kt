package com.hishow.terminal33

object TerminalKeys {
    fun ctrl(letter: Char): String = ((letter.uppercaseChar().code and 0x1f).toChar()).toString()
    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"
}
