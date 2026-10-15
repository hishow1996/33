package io.translatetube.local.yt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class CancellableNewPipeHttpTest {
    @Test
    fun ownerCancellationInterruptsBlockedYoutubeResponseRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("subtitle payload")
                    .setBodyDelay(500, TimeUnit.MILLISECONDS)
            )
            server.start()
            val ownerJob = SupervisorJob()
            val client = OkHttpClient.Builder()
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            try {
                val request = Request.Builder().url(server.url("/subtitle")).build()
                val read = async(Dispatchers.IO) {
                    executeCancellableHttpCall(client.newCall(request), ownerJob) { response ->
                        response.body!!.byteStream().use { input -> input.read() }
                    }
                }

                // Allow response headers to arrive; the body read is delayed by the test server.
                delay(200)
                ownerJob.cancel()
                val error = withTimeout(2_000) {
                    runCatching { read.await() }.exceptionOrNull()
                }
                assertTrue(
                    "Cancelling an in-flight YouTube request should surface coroutine cancellation",
                    error is CancellationException,
                )
            } finally {
                ownerJob.cancel()
            }
        }
    }
}
