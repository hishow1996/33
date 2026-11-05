package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLanguageDetectorNewScriptTest {
    @Test
    fun detectsBengaliKhmerLaoAndMyanmarFromTheirScripts() {
        assertEquals("bn", SubtitleLanguageDetector.detect("ডঃ রহমান এসেছেন।"))
        assertEquals("km", SubtitleLanguageDetector.detect("លោក សុខ មកដល់ហើយ។"))
        assertEquals("lo", SubtitleLanguageDetector.detect("ດຣ ສົມຊາຍ ມາແລ້ວ"))
        assertEquals("my", SubtitleLanguageDetector.detect("ဒေါက်တာ အောင်မြင့် လာပါပြီ"))
    }

    @Test
    fun normalizesProviderNamesAndIsoCodesForAdditionalScripts() {
        assertEquals("km", SubtitleLanguageDetector.resolveTranscriptionLanguage("Khmer", "សុខ"))
        assertEquals("lo", SubtitleLanguageDetector.resolveTranscriptionLanguage("lao", "ສົມຊາຍ"))
        assertEquals("my", SubtitleLanguageDetector.resolveTranscriptionLanguage("Burmese", "အောင်မြင့်"))
        assertEquals("bn", SubtitleLanguageDetector.resolveTranscriptionLanguage("ben", "রহমান"))
    }
}
