package com.hishow.terminal33

import android.content.Context

/** Registry for independent terminal sessions; PTY lifetime remains explicit and safe. */
class TerminalSessionManager(private val context: Context, private val maxSessions: Int = 4) {
    data class Session(val id: Int, val title: String, val terminal: UbuntuSession)
    private val sessions = LinkedHashMap<Int, Session>()
    var activeId: Int? = null
        private set

    fun list(): List<Session> = sessions.values.toList()

    fun create(): Session? {
        if (sessions.size >= maxSessions) return null
        val id = (1..999).firstOrNull { !sessions.containsKey(it) } ?: return null
        return Session(id, "ubuntu $id", UbuntuSession(context)).also {
            sessions[id] = it
            activeId = id
        }
    }

    fun switch(id: Int): Boolean = if (sessions.containsKey(id)) { activeId = id; true } else false

    fun remove(id: Int) {
        sessions.remove(id)?.terminal?.close()
        if (activeId == id) activeId = sessions.keys.lastOrNull()
    }

    fun active(): Session? = activeId?.let { sessions[it] }
    fun closeAll() { sessions.values.forEach { it.terminal.close() }; sessions.clear(); activeId = null }
}

/** Session-local PTY wrapper used by the UI. */
class UbuntuSession(private val context: Context) {
    private var pty: NativePty? = null

    fun start(onOutput: (String) -> Unit, onExit: () -> Unit) {
        val rootfs = TerminalPaths.rootfs(context)
        val proot = java.io.File(TerminalPaths.ubuntuHome(context), "proot")
        require(proot.isFile) { "PRoot runtime is not installed" }
        val args = listOf(proot.absolutePath, "-0", "-r", rootfs.absolutePath,
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            "-b", "/sdcard:/mnt/shared", "/bin/bash", "--login")
        pty = NativePty().also { p ->
            p.start(args, rootfs.absolutePath, onOutput, onExit)
            p.resize(32, 120)
        }
    }

    fun write(text: String) { pty?.write(text) }
    fun resize(rows: Int, columns: Int) { pty?.resize(rows, columns) }
    fun close() { pty?.close(); pty = null }
}
