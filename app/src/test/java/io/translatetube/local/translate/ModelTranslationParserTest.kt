package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTranslationParserTest {
    @Test
    fun acceptsCompleteZeroBasedResultsIncludingEmptyRows() {
        assertEquals(
            listOf("hello", ""),
            parseModelTranslations("0|hello\n1|", 2),
        )
    }

    @Test
    fun rejectsIncompleteIndexedResultsInsteadOfReturningMisalignedBlanks() {
        val error = runCatching {
            parseModelTranslations("0|hello", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun rejectsDuplicateIndexedRows() {
        val error = runCatching {
            parseModelTranslations("0|first\n0|duplicate\n1|second", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }
}
