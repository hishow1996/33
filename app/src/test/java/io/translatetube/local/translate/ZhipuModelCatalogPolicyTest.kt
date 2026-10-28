package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ZhipuModelCatalogPolicyTest {
    @Test
    fun officialZhipuCatalogKeepsDocumentedGlmFlashSelectableWhenOmitted() {
        assertEquals(
            listOf("glm-4.6", "glm-4.7-flash", "glm-5"),
            mergeKnownZhipuModels(
                "https://open.bigmodel.cn/api/paas/v4",
                listOf("glm-5", "glm-4.6")
            )
        )
    }

    @Test
    fun existingGlmFlashEntryIsNotDuplicated() {
        assertEquals(
            listOf("glm-4.7-flash", "glm-5"),
            mergeKnownZhipuModels(
                "https://open.bigmodel.cn/api/paas/v4",
                listOf("glm-4.7-flash", "glm-5")
            )
        )
    }

    @Test
    fun otherProvidersAreNotGivenZhipuSpecificModels() {
        assertEquals(
            listOf("deepseek-chat"),
            mergeKnownZhipuModels(
                "https://api.deepseek.com/v1",
                listOf("deepseek-chat")
            )
        )
    }
}
