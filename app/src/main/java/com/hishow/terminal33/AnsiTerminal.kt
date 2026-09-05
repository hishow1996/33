package com.hishow.terminal33

/** Lightweight VT/ANSI normalizer. Keeps printable text and handles common screen controls. */
class AnsiTerminal {
    private var pending = ""
    fun feed(input: String): String {
        val s = pending + input
        pending = ""
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u001B') {
                if (i + 1 >= s.length) { pending = s.substring(i); break }
                val n = s[i + 1]
                if (n == '[') {
                    var j = i + 2
                    while (j < s.length && (s[j] < '@' || s[j] > '~')) j++
                    if (j >= s.length) { pending = s.substring(i); break }
                    i = j + 1
                    continue
                }
                if (n == ']') {
                    var j = i + 2
                    while (j < s.length) {
                        if (s[j] == '\u0007') { j++; break }
                        if (s[j] == '\u001B' && j + 1 < s.length && s[j + 1] == '\\') { j += 2; break }
                        j++
                    }
                    if (j > s.length) { pending = s.substring(i); break }
                    i = j
                    continue
                }
                i += 2
                continue
            }
            if (c == '\r') { i++; continue }
            if (c == '\u0000' || c == '\u0007') { i++; continue }
            out.append(c)
            i++
        }
        return out.toString()
    }
    fun reset() { pending = "" }
}
