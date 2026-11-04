package io.translatetube.local.asr

import org.junit.Test

class AudioExtractResampleTest {
    @Test(expected = IllegalStateException::class)
    fun rejectsWholeArrayUpsamplingThatExceedsMemoryBudget() {
        // 1.1M samples at 1kHz become 17.6M samples at 16kHz,
        // exceeding the bounded whole-array helper's 32 MiB output budget.
        AudioExtract.resample(ShortArray(1_100_000), 1_000, 16_000)
    }
}
