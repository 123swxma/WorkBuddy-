package com.workbuddy.proxy.core

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/** 内存环形日志：面板能看到最近请求与错误，不落盘、不涨内存。 */
object Logs {

    private const val MAX = 400
    private val ring: MutableList<Entry> = Collections.synchronizedList(ArrayList())

    data class Entry(val at: Long, val level: String, val message: String) {
        fun timeText(): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }

    fun info(message: String) = add("info", message)

    fun warn(message: String) = add("warn", message)

    fun error(message: String) = add("error", message)

    private fun add(level: String, message: String) {
        if (!Tuning.keepLogs) return
        synchronized(ring) {
            ring.add(Entry(System.currentTimeMillis(), level, message.take(500)))
            while (ring.size > MAX) ring.removeAt(0)
        }
    }

    fun recent(n: Int = 100): List<Entry> = synchronized(ring) {
        val from = maxOf(0, ring.size - n)
        ArrayList(ring.subList(from, ring.size))
    }

    fun clear() = synchronized(ring) { ring.clear() }
}
