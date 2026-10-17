package io.translatetube.local.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpVersionPolicyTest {
    @Test
    fun choosesLatestStableVersionInsteadOfDevelopmentBuild() {
        val metadata = """
            {
              "info": {"version": "2026.10.10.233141.dev0"},
              "releases": {
                "2026.8.19": [{}],
                "2026.9.27.232945.dev0": [{}],
                "2026.3.17": [{}],
                "2025.12.8": [{}],
                "2026.10.10.233141.dev0": [{}],
                "2026.9.1rc1": [{}]
              }
            }
        """.trimIndent()
        assertEquals("2026.8.19", YtDlpVersionPolicy.latestStableFromPypiJson(metadata))
    }

    @Test
    fun readsInstalledVersionFromActualYtDlpCommandOutput() {
        assertEquals(
            "2026.10.10",
            YtDlpVersionPolicy.installedVersionFromOutput(
                """Python 3.12.0
2026.10.10
""",
            ),
        )
        assertEquals(
            "2026.10.10",
            YtDlpVersionPolicy.installedVersionFromOutput("yt-dlp version 2026.10.10"),
        )
        assertEquals(
            "2026.10.10",
            YtDlpVersionPolicy.installedVersionFromOutput("YT-DLP 2026.10.10"),
        )
        assertNull(
            YtDlpVersionPolicy.installedVersionFromOutput(
                """yt-dlp --version
2026.10.10.dev0
""",
            ),
        )
        assertNull(
            YtDlpVersionPolicy.installedVersionFromOutput("yt-dlp version 2026.10.10.233141.dev0"),
        )
        assertNull(YtDlpVersionPolicy.installedVersionFromOutput(""))
    }

    @Test
    fun acceptsOnlyStableDateTags() {
        assertTrue(YtDlpVersionPolicy.isStableTag("2026.8.19"))
        assertFalse(YtDlpVersionPolicy.isStableTag("2026.10.10.233141.dev0"))
        assertFalse(YtDlpVersionPolicy.isStableTag("2026.9.1rc1"))
    }

    @Test
    fun readsStableTagFromGithubRelease() {
        assertEquals(
            "2026.8.19",
            YtDlpVersionPolicy.stableGitHubTag("""{"tag_name":"2026.8.19"}"""),
        )
        assertNull(YtDlpVersionPolicy.stableGitHubTag("""{"tag_name":"2026.10.10.233141.dev0"}"""))
    }

    @Test
    fun readsStableTagFromLatestReleaseRedirect() {
        assertEquals(
            "2026.8.19",
            YtDlpVersionPolicy.stableTagFromReleasePath("/yt-dlp/yt-dlp/releases/tag/2026.8.19"),
        )
        assertNull(
            YtDlpVersionPolicy.stableTagFromReleasePath("/yt-dlp/yt-dlp/releases/latest"),
        )
    }
}
