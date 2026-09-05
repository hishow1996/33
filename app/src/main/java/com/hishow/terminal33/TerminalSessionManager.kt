package com.hishow.terminal33

/** UI-facing session registry. PTY lifecycle stays owned by MainActivity/UbuntuTerminal. */
class TerminalSessionManager(private val maxSessions: Int = 4) {
    data class Session(val id: Int, val title: String)
    private val sessions = LinkedHashMap<Int, Session>()

    fun list(): List<Session> = sessions.values.toList()

    fun create(): Session? {
        if (sessions.size >= maxSessions) return null
        val id = (1..999).firstOrNull { !sessions.containsKey(it) } ?: return null
        return Session(id, "ubuntu $id").also { sessions[id] = it }
    }

    fun remove(id: Int) { sessions.remove(id) }
    fun closeAll() { sessions.clear() }
}
