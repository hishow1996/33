package com.hishow.terminal33

import java.io.Closeable
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/** Small JNI wrapper around a Linux-style pseudo terminal. */
internal class NativePty : Closeable {
    private var masterFd: Int = -1
    private var childPid: Long = -1
    private val running = AtomicBoolean(false)

    init {
        System.loadLibrary("terminal33pty")
        nativeInit()
    }

    fun start(argv: List<String>, cwd: String, onOutput: (String) -> Unit, onExit: () -> Unit) {
        check(argv.isNotEmpty()) { "PTY argv is empty" }
        check(nativeStart(argv.toTypedArray(), cwd)) { "Unable to start PTY" }
        running.set(true)
        Thread {
            val buffer = ByteArray(32 * 1024)
            try {
                while (running.get()) {
                    val n = nativeRead(buffer)
                    if (n <= 0) break
                    onOutput(buffer.copyOf(n).toString(Charset.forName("UTF-8")))
                }
            } finally {
                running.set(false)
                onExit()
            }
        }.apply {
            name = "ubuntu-pty-reader"
            isDaemon = true
            start()
        }
    }

    fun write(text: String) {
        if (!running.get()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val n = nativeWrite(bytes.copyOfRange(offset, bytes.size), bytes.size - offset)
            if (n <= 0) break
            offset += n
        }
    }

    fun resize(rows: Int, columns: Int) {
        if (running.get()) nativeResize(rows.coerceAtLeast(1), columns.coerceAtLeast(1))
    }

    override fun close() {
        running.set(false)
        nativeClose()
    }

    private external fun nativeInit()
    private external fun nativeStart(argv: Array<String>, cwd: String): Boolean
    private external fun nativeRead(buffer: ByteArray): Int
    private external fun nativeWrite(data: ByteArray, length: Int): Int
    private external fun nativeResize(rows: Int, cols: Int)
    private external fun nativeClose()
}
