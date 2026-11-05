package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAcceptancePolicyTest {
    @Test
    fun adaptiveAcceptorDoesNotRetryReadableRowsForSoftHeuristicWarnings() {
        val accept = subtitleTranslationAcceptor("en", "zh-CN")

        assertTrue(accept("I bought a car.", "我卖了一辆车。"))
        assertTrue(accept("I have 3 cats.", "我有3只猫和5只狗。"))
        assertTrue(accept("The software will be updated later.", "這個軟體後續會持續更新。"))

        assertTrue(accept("I bought a car.", "我买了一辆车。"))
        assertTrue(accept("I have 3 cats.", "我有3只猫。"))
    }

    @Test
    fun shortUnchangedNameExceptionCannotBypassNumericOrSemanticChecks() {
        val accept = subtitleShortUntranslatedAcceptor("en", "zh-CN")

        assertTrue(accept("John", "John"))
        assertFalse(accept("H-1B", "H-2B"))
        assertFalse(accept("I bought a car.", "我卖了一辆车。"))
    }

    @Test
    fun ChineseSourceCaptionsAreNotRejectedForRetainingTheirOriginalScript() {
        val accept = subtitleTranslationAcceptor("zh-Hant", "zh-CN")
        assertTrue(accept("這是原始字幕。", "這是原始字幕。"))
    }
    @Test
    fun adaptiveAcceptorUsesStructuralAndLanguageChecksForReadableOutput() {
        assertTrue(subtitleTranslationAcceptor("en", "zh-CN")(
            "The first route is 3 km and the second route is 5 m.",
            "第一条路线是5公里，第二条路线是3米。",
        ))
        assertTrue(subtitleTranslationAcceptor("fr", "zh-CN")(
            "Je ne sais pas.",
            "我知道。",
        ))
        assertTrue(subtitleTranslationAcceptor("en", "zh-CN")(
            "Where are you?",
            "我在这里。",
        ))
        assertTrue(subtitleTranslationAcceptor("fr", "zh-CN")(
            "Je ne sais pas.",
            "我不知道。",
        ))
    }

    @Test
    fun autoLanguageAdaptiveChecksUseOnlyHighConfidenceSourceSignals() {
        val accept = subtitleTranslationAcceptor("auto", "zh-CN")
        assertTrue(accept("I do not agree.", "我同意。"))
        assertTrue(accept("Je ne sais pas.", "我知道。"))
        assertTrue(accept("行きません。", "我去。"))
        assertTrue(accept("I do not agree.", "我不同意。"))
        assertTrue(accept("Sam arrived yesterday.", "萨姆昨天到了。"))
    }

}
