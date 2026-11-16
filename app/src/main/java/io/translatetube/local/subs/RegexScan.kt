package io.translatetube.local.subs

import java.util.regex.Matcher

/**
 * 在整段大文本（例如 1~2 小时视频的字幕响应，几十万字符）上逐个找匹配。
 *
 * 不要在大文本上用 Kotlin 的 `Regex.findAll`：它每找到一个匹配，就为"下一个匹配"重新创建一个
 * java.util.regex.Matcher。Android 的 Matcher 创建时会把整段输入再复制一份到原生内存，
 * 而 Java 堆里的对象很小，不会触发 GC 回收，原生内存就会一直涨。字幕越长、标签越多，涨得越快，
 * 长视频上能把系统内存耗尽，表现为应用消失、整个桌面重启。这里整段文本只用一个 Matcher。
 */
internal object RegexScan {
    fun count(regex: Regex, input: CharSequence): Int {
        val matcher = regex.toPattern().matcher(input)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    /** 对每个匹配调用 [action]；整个过程只创建一个 Matcher。[action] 里不要保留 matcher。 */
    inline fun forEachMatch(regex: Regex, input: CharSequence, action: (index: Int, matcher: Matcher) -> Unit) {
        val matcher = regex.toPattern().matcher(input)
        var index = 0
        while (matcher.find()) action(index++, matcher)
    }
}
