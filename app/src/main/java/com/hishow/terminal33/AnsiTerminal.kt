package com.hishow.terminal33

/** Incremental ANSI/VT normalizer used by the lightweight text renderer. */
class AnsiTerminal {
    private var pending = ""

    fun feed(input: String): String {
        val s = pending + input
        pending = ""
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\u001b' -> {
                    if (i + 1 >= s.length) { pending = s.substring(i); break }
                    val kind = s[i + 1]
                    if (kind == '[') {
                        var j = i + 2
                        while (j < s.length && (s[j] < '@' || s[j] > '~')) j++
                        if (j >= s.length) { pending = s.substring(i); break }
                        i = j + 1
                    } else if (kind == ']') {
                        var j = i + 2
                        while (j < s.length && s[j] != '\u0007') j++
                        if (j >= s.length) { pending = s.substring(i); break }
                        i = j + 1
                    } else i += 2
                }
                '\u0000', '\u0007' -> i++
                '\r' -> { if (i + 1 < s.length && s[i + 1] == '\n') i += 2 else i++ }
                else -> { out.append(s[i]); i++ }
            }
        }
        return out.toString()
    }

    fun reset() { pending = "" }
}
