package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingSourceVolumePolicyTest {
    @Test
    fun sourceIsMutedOnlyAfterAudibleDubbingActuallyStarts() {
        assertEquals(1f, DubbingSourceVolumePolicy.sourceGain(1f, 1f, true, false), 0f)
        assertEquals(0f, DubbingSourceVolumePolicy.sourceGain(1f, 1f, true, true), 0f)
    }

    @Test
    fun sourceReturnsAfterDubbingStopsOrIsDisabled() {
        assertEquals(1f, DubbingSourceVolumePolicy.sourceGain(1f, 1f, false, true), 0f)
        assertEquals(1f, DubbingSourceVolumePolicy.sourceGain(1f, 1f, true, false), 0f)
    }

    @Test
    fun zeroDubbingVolumeDoesNotMuteTheOnlyAudibleNarration() {
        assertEquals(1f, DubbingSourceVolumePolicy.sourceGain(1f, 0f, true, true), 0f)
    }

    @Test
    fun originalMutePreferenceAlwaysWins() {
        assertEquals(0f, DubbingSourceVolumePolicy.sourceGain(0f, 1f, true, false), 0f)
        assertEquals(0f, DubbingSourceVolumePolicy.sourceGain(0f, 1f, true, true), 0f)
    }

    @Test
    fun invalidVolumeInputsFailSafeWithoutLeakingSourceAudioDuringSpeech() {
        assertEquals(0f, DubbingSourceVolumePolicy.sourceGain(Float.NaN, 1f, true, true), 0f)
        assertEquals(0f, DubbingSourceVolumePolicy.sourceGain(1f, Float.NaN, true, true), 0f)
    }
}
