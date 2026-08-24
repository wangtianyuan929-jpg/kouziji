package com.kouziji.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 扣字日志与事件管理器
 */
object LogManager {
    enum class Level {
        INFO, SUCCESS, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val message: String
    ) {
        val timeString: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }

    private const val MAX_LOGS = 500
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    fun log(level: Level, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        logs.add(entry)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        listeners.forEach { it(entry) }
    }

    fun i(msg: String) = log(Level.INFO, msg)
    fun s(msg: String) = log(Level.SUCCESS, msg)
    fun w(msg: String) = log(Level.WARN, msg)
    fun e(msg: String) = log(Level.ERROR, msg)

    fun getLogs(): List<LogEntry> = logs.toList()

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        logs.clear()
    }
}
