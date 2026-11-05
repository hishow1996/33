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
    fun detectsTamilTeluguKannadaMalayalamSinhalaGeorgianArmenianAndEthiopic() {
        assertEquals("ta", SubtitleLanguageDetector.detect("சென்னை இன்று அழகாக உள்ளது"))
        assertEquals("te", SubtitleLanguageDetector.detect("హైదరాబాద్ చాలా పెద్ద నగరం"))
        assertEquals("kn", SubtitleLanguageDetector.detect("ಬೆಂಗಳೂರು ಕರ್ನಾಟಕದ ರಾಜಧಾನಿ"))
        assertEquals("ml", SubtitleLanguageDetector.detect("കൊച്ചി കേരളത്തിലെ നഗരമാണ്"))
        assertEquals("si", SubtitleLanguageDetector.detect("කොළඹ ශ්‍රී ලංකාවේ අගනුවරයි"))
        assertEquals("ka", SubtitleLanguageDetector.detect("თბილისი საქართველოს დედაქალაქია"))
        assertEquals("hy", SubtitleLanguageDetector.detect("Երևանը Հայաստանի մայրաքաղաքն է"))
        assertEquals("am", SubtitleLanguageDetector.detect("አዲስ አበባ የኢትዮጵያ ዋና ከተማ ናት"))
    }

    @Test
    fun honorsProviderLanguageForSharedArabicAndEthiopicScripts() {
        assertEquals("fa", SubtitleLanguageDetector.resolveTranscriptionLanguage("Persian", "سلام دنیا"))
        assertEquals("ur", SubtitleLanguageDetector.resolveTranscriptionLanguage("Urdu", "السلام علیکم"))
        assertEquals("ti", SubtitleLanguageDetector.resolveTranscriptionLanguage("Tigrinya", "ሰላም"))
    }

    @Test
    fun normalizesProviderNamesAndIsoCodesForAdditionalScripts() {
        assertEquals("km", SubtitleLanguageDetector.resolveTranscriptionLanguage("Khmer", "សុខ"))
        assertEquals("lo", SubtitleLanguageDetector.resolveTranscriptionLanguage("lao", "ສົມຊາຍ"))
        assertEquals("my", SubtitleLanguageDetector.resolveTranscriptionLanguage("Burmese", "အောင်မြင့်"))
        assertEquals("bn", SubtitleLanguageDetector.resolveTranscriptionLanguage("ben", "রহমান"))
        assertEquals("ta", SubtitleLanguageDetector.resolveTranscriptionLanguage("Tamil", "ரவி"))
        assertEquals("te", SubtitleLanguageDetector.resolveTranscriptionLanguage("tel", "రవి"))
        assertEquals("kn", SubtitleLanguageDetector.resolveTranscriptionLanguage("Kannada", "ರವಿ"))
        assertEquals("ml", SubtitleLanguageDetector.resolveTranscriptionLanguage("Malayalam", "രവി"))
        assertEquals("si", SubtitleLanguageDetector.resolveTranscriptionLanguage("Sinhala", "නිමල්"))
        assertEquals("ka", SubtitleLanguageDetector.resolveTranscriptionLanguage("Georgian", "დავით"))
        assertEquals("hy", SubtitleLanguageDetector.resolveTranscriptionLanguage("Armenian", "Դավիթ"))
    }
}
