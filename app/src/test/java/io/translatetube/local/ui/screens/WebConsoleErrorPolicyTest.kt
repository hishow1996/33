package io.translatetube.local.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebConsoleErrorPolicyTest {
    private val knownError =
        "Uncaught (in promise) Error: detectIncognito: Browser not supported"

    @Test
    fun ignoresKnownUnsupportedBrowserProbeFromThirdPartyScript() {
        assertTrue(
            WebConsoleErrorPolicy.shouldIgnoreKnownThirdPartyError(
                message = knownError,
                pageHost = "cn.example.com",
                scriptHost = "assets.example-cdn.com",
            )
        )
    }

    @Test
    fun keepsTheSameErrorWhenItComesFromThePageOrigin() {
        assertFalse(
            WebConsoleErrorPolicy.shouldIgnoreKnownThirdPartyError(
                message = knownError,
                pageHost = "cn.example.com",
                scriptHost = "cn.example.com",
            )
        )
    }

    @Test
    fun keepsUnrelatedThirdPartyJavaScriptErrors() {
        assertFalse(
            WebConsoleErrorPolicy.shouldIgnoreKnownThirdPartyError(
                message = "Uncaught TypeError: player.attachMedia is not a function",
                pageHost = "cn.example.com",
                scriptHost = "assets.example-cdn.com",
            )
        )
    }

    @Test
    fun doesNotIgnoreWhenScriptOriginIsMissing() {
        assertFalse(
            WebConsoleErrorPolicy.shouldIgnoreKnownThirdPartyError(
                message = knownError,
                pageHost = "cn.example.com",
                scriptHost = "",
            )
        )
        assertFalse(
            WebConsoleErrorPolicy.shouldIgnoreKnownThirdPartyError(
                message = knownError,
                pageHost = "cn.example.com",
                scriptHost = "未知",
            )
        )
    }
}
