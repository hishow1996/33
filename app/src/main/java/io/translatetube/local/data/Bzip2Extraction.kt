package io.translatetube.local.data

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * .tar.bz2 模型包的统一解压入口：优先多线程解码，并行解码自身出问题时退回单线程。
 * 并行解码失败时，上一次尝试写出的内容由调用方在 [withFallback] 的 onFallback 里清理。
 */
internal object Bzip2Extraction {
    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** [threads] > 1 用多线程解码，否则用带缓冲的单线程解码。 */
    fun open(archive: File, threads: Int): InputStream =
        if (threads > 1) {
            ParallelBzip2InputStream(archive, threads)
        } else {
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 256 * 1024))
        }

    /** 先用多线程跑 [attempt]；只有并行解码自身失败时才清理并用单线程重跑一次。 */
    inline fun <T> withFallback(
        onFallback: (Throwable) -> Unit = {},
        attempt: (threads: Int) -> T,
    ): T {
        val threads = defaultThreads()
        if (threads > 1) {
            try {
                return attempt(threads)
            } catch (e: ParallelBzip2InputStream.ParallelBzip2Exception) {
                onFallback(e)
            }
        }
        return attempt(1)
    }
}
