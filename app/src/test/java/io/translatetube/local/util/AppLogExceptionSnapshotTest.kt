package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AppLogExceptionSnapshotTest {
    @Test
    fun snapshotKeepsCauseAndSuppressedAsSeparateBranchesWithTheirOwnFrames() {
        val rootCause = SocketTimeoutException("read timed out").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "io.translatetube.local.translate.HttpTransport",
                    "readResponse",
                    "HttpTransport.kt",
                    214,
                ),
            )
        }
        val failure = IllegalStateException("translation request failed", rootCause).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "io.translatetube.local.translate.TranslationEngine",
                    "translate",
                    "TranslationEngine.kt",
                    88,
                ),
            )
            addSuppressed(
                IOException("response close failed").apply {
                    stackTrace = arrayOf(
                        StackTraceElement("okhttp3.ResponseBody", "close", "ResponseBody.kt", 41),
                    )
                },
            )
        }

        val snapshot = AppLogStore.snapshotExceptionTree(failure)

        assertEquals("java.lang.IllegalStateException", snapshot.type)
        assertEquals("translation request failed", snapshot.message)
        assertEquals("io.translatetube.local.translate.TranslationEngine", snapshot.stackFrames.first().className)
        assertEquals(88, snapshot.stackFrames.first().lineNumber)
        assertNotNull(snapshot.cause)
        assertEquals("java.net.SocketTimeoutException", snapshot.cause?.type)
        assertEquals("read timed out", snapshot.cause?.message)
        assertEquals("io.translatetube.local.translate.HttpTransport", snapshot.cause?.stackFrames?.first()?.className)
        assertEquals(1, snapshot.suppressed.size)
        assertEquals("java.io.IOException", snapshot.suppressed.first().type)
        assertEquals("response close failed", snapshot.suppressed.first().message)
        assertEquals("okhttp3.ResponseBody", snapshot.suppressed.first().stackFrames.first().className)
    }

    @Test
    fun snapshotStopsCauseCyclesWithoutDuplicatingTheSameThrowable() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        first.initCause(second)
        second.initCause(first)

        val snapshot = AppLogStore.snapshotExceptionTree(first)

        assertEquals("java.lang.IllegalStateException", snapshot.type)
        assertEquals("java.lang.IllegalArgumentException", snapshot.cause?.type)
        assertNull(snapshot.cause?.cause)
    }

    @Test
    fun snapshotDoesNotExceedThePerExceptionFrameLimit() {
        val failure = IllegalStateException("too many frames").apply {
            stackTrace = Array(80) { index ->
                StackTraceElement("sample.Frame$index", "run", "Frame.kt", index)
            }
        }

        val snapshot = AppLogStore.snapshotExceptionTree(failure)

        assertEquals(48, snapshot.stackFrames.size)
    }
    @Test
    fun deeplyNestedSuppressedTreeIsBoundedAndMarkedAsTruncated() {
        val root = IllegalStateException("root")
        repeat(4) { branchIndex ->
            val branch = IllegalArgumentException("branch-$branchIndex")
            repeat(4) { firstIndex ->
                val node = IOException("nested-$branchIndex-$firstIndex")
                repeat(4) { secondIndex ->
                    node.addSuppressed(IOException("deep-$branchIndex-$firstIndex-$secondIndex"))
                }
                branch.addSuppressed(node)
            }
            root.addSuppressed(branch)
        }

        val snapshot = AppLogStore.snapshotExceptionTree(root)

        fun count(node: AppLogExceptionSnapshot?): Int =
            if (node == null) 0 else 1 + count(node.cause) + node.suppressed.sumOf { count(it) }

        fun hasTruncation(node: AppLogExceptionSnapshot?): Boolean =
            node != null && (
                node.truncated ||
                    hasTruncation(node.cause) ||
                    node.suppressed.any { hasTruncation(it) }
                )

        assertEquals(24, count(snapshot))
        assertEquals("root", snapshot.message)
        assertTrue(hasTruncation(snapshot))
    }

}
