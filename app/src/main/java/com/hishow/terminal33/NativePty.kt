package com.hishow.terminal33

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor

internal object NativePty {
    init { System.loadLibrary("terminal_pty") }

    external fun spawn(argv: Array<String>, cwd: String): Int
    external fun resize(fd: Int, rows: Int, cols: Int): Int
    external fun close(fd: Int): Int
}

internal class PtySession(private val fd: Int) {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(fd)
    private val descriptor: FileDescriptor = pfd.fileDescriptor

    fun read(buffer: ByteArray): Int = android.system.Os.read(descriptor, buffer, 0, buffer.size)

    fun write(bytes: ByteArray) = android.system.Os.write(descriptor, bytes, 0, bytes.size)

    fun resize(rows: Int, cols: Int) = NativePty.resize(pfd.fd, rows, cols)

    fun close() = runCatching { pfd.close() }
}
