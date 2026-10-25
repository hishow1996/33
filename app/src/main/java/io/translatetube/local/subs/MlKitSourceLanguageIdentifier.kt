package io.translatetube.local.subs

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * On-device source-language identification for Latin-script captions that the Unicode-script
 * detector cannot distinguish. Only a bounded, in-memory sample of subtitle text is inspected.
 */
object MlKitSourceLanguageIdentifier {
    suspend fun detect(cues: Iterable<String>, maxCues: Int = 120): String {
        if (maxCues <= 0) return "auto"
        val sample = StringBuilder()
        var cueCount = 0
        for (cue in cues) {
            if (sample.length >= 12_000 || cueCount >= maxCues) break
            if (cue.isBlank()) continue
            cueCount++
            if (sample.isNotEmpty()) sample.append(' ')
            val remaining = 12_000 - sample.length
            if (remaining <= 0) break
            sample.append(cue, 0, minOf(cue.length, remaining))
        }
        // Very short text is ambiguous; retain auto-detect behavior instead of guessing.
        if (sample.length < 24 || sample.count { it.isLetter() } < 8) return "auto"

        val identifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.55f)
                .build(),
        )
        return try {
            val code = identifier.identifyLanguage(sample.toString()).await()
            if (code == "und" || code.isBlank()) "auto" else normalize(code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Identification is a hint, not a reason to abort translation.
            "auto"
        } finally {
            identifier.close()
        }
    }

    private fun normalize(code: String): String =
        when (code.trim().replace('_', '-').lowercase(Locale.ROOT).substringBefore('-')) {
            "iw" -> "he"
            "in" -> "id"
            "nb", "nn" -> "no"
            else -> code.trim().replace('_', '-').lowercase(Locale.ROOT).substringBefore('-')
        }
}
