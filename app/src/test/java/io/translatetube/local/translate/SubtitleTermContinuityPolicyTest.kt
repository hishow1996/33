package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTermContinuityPolicyTest {
    @Test
    fun extractsStableVisibleNamesAndProductTermsButNotCommonSentenceWords() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Sam met OpenAI in New York and later used iPhone.")
        )
        assertTrue(terms.contains("sam"))
        assertTrue(terms.contains("openai"))
        assertTrue(terms.contains("new"))
        assertTrue(terms.contains("york"))
        assertTrue(terms.contains("iphone"))
        assertFalse(terms.contains("the"))
        assertFalse(terms.contains("and"))
    }

    @Test
    fun matchesTermsCaseInsensitivelyWithoutGuessingTheirTranslations() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(listOf("Sam called OpenAI."))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm("Yesterday SAM called.", terms))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm("OpenAI replied.", terms))
        assertFalse(SubtitleTermContinuityPolicy.containsStableTerm("The meeting starts.", terms))
    }
    @Test
    fun filtersFrenchSentenceOpenersAndKeepsNamesAcrossLatinLanguageTracks() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Je parle avec Sam, Élodie et OpenAI."),
            sourceLanguage = "fr",
        )
        assertFalse(terms.contains("je"))
        assertTrue(terms.contains("sam"))
        assertTrue(terms.contains("élodie"))
        assertTrue(terms.contains("openai"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "Nous reparlons avec Sam et Élodie.",
            terms,
            sourceLanguage = "fr",
        ))
    }

    @Test
    fun recognizesConservativeJapaneseAndKoreanNameMarkers() {
        val japaneseTerms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("田中さんはオープンAIを使います。"),
            sourceLanguage = "ja",
        )
        assertTrue(japaneseTerms.contains("田中"))
        assertTrue(japaneseTerms.contains("オープン"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "田中先生も来ます。",
            japaneseTerms,
            sourceLanguage = "ja",
        ))

        val koreanTerms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("김민수 씨가 OpenAI를 소개합니다."),
            sourceLanguage = "ko",
        )
        assertTrue(koreanTerms.contains("김민수"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "김민수님이 다시 말합니다.",
            koreanTerms,
            sourceLanguage = "ko",
        ))
    }

    @Test
    fun extractsCyrillicAndGreekProperNamesButFiltersSentenceOpenersAndGreetings() {
        val russianTerms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Сегодня Александр Пушкин встретил Владимира Путина."),
            sourceLanguage = "ru",
        )
        assertTrue(russianTerms.contains("александр"))
        assertTrue(russianTerms.contains("пушкин"))
        assertTrue(russianTerms.contains("владимира"))
        assertTrue(russianTerms.contains("путина"))
        assertFalse(russianTerms.contains("сегодня"))
        assertFalse(russianTerms.contains("привет"))

        val greekTerms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Αλέξανδρος Ωνάσης είπε καλημέρα."),
            sourceLanguage = "el",
        )
        assertTrue(greekTerms.contains("αλέξανδρος"))
        assertTrue(greekTerms.contains("ωνάσης"))
        assertFalse(greekTerms.contains("καλημέρα"))
    }

    @Test
    fun recognizesKnownNamesAndPlacesInScriptsWithoutLetterCase() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("東京と大阪、서울とدبي、ירושלים。"),
            sourceLanguage = "auto",
        )
        assertTrue(terms.contains("東京"))
        assertTrue(terms.contains("大阪"))
        assertTrue(terms.contains("서울"))
        assertTrue(terms.contains("دبي"))
        assertTrue(terms.contains("ירושלים"))

        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm("東京にも行きます。", terms, "ja"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm("서울에 다시 왔습니다.", terms, "ko"))
    }

    @Test
    fun autoSourceLanguageRecognizesJapaneseAndKoreanTermScriptsConservatively() {
        assertEquals("ja", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("田中さんはオープンAIを使います。"),
            "auto",
        ))
        assertEquals("ko", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("김민수 씨가 다시 왔습니다."),
            "auto",
        ))
        assertEquals("auto", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("Sam arrived yesterday."),
            "auto",
        ))
    }

    @Test
    fun recognizesArabicHebrewAndDevanagariNamesFromHonorificsAcrossLaterCues() {
        val arabic = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("الدكتور محمد وصل إلى دبي."),
            sourceLanguage = "ar",
        )
        assertTrue(arabic.contains("محمد"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "محمد سيتحدث لاحقاً.", arabic, "ar",
        ))
        assertFalse(SubtitleTermContinuityPolicy.containsStableTerm(
            "محمود سيتحدث لاحقاً.", arabic, "ar",
        ))

        val hebrew = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("ד״ר דוד הגיע לתל אביב."),
            sourceLanguage = "he",
        )
        assertTrue(hebrew.contains("דוד"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "דוד ידבר מחר.", hebrew, "he",
        ))

        val hindi = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("डॉ. नरेंद्र आज आए।"),
            sourceLanguage = "hi",
        )
        assertTrue(hindi.contains("नरेंद्र"))
        assertTrue(SubtitleTermContinuityPolicy.containsStableTerm(
            "नरेंद्र फिर आए।", hindi, "hi",
        ))
    }

    @Test
    fun autoLanguageRecognizesArabicHebrewAndDevanagariNameContexts() {
        assertEquals("ar", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("الدكتور محمد وصل اليوم."), "auto",
        ))
        assertEquals("he", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("ד״ר דוד הגיע היום."), "auto",
        ))
        assertEquals("hi", SubtitleTermContinuityPolicy.resolveLanguage(
            listOf("डॉ. नरेंद्र आज आए।"), "auto",
        ))
    }

    @Test
    fun cyrillicNameBesideJapaneseTextIsStillFoundAndNeverBreaksInitialization() {
        // The name regex uses explicit code-point ranges (not \\p{IsCyrillic}-style script names,
        // which Android 9's ICU engine rejects, making the whole object fail to initialize).
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("私はАлександрと話した。"),
            sourceLanguage = "ja",
        )
        assertTrue(terms.contains("александр"))
    }
}
