package com.siliconprime.tabletmirror.host

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An append-only record of who connected to this tablet and when.
 *
 * For a till, "which device drove this screen at 14:32" is a question worth being
 * able to answer after the fact — for reconciling a disputed transaction as much
 * as for spotting an intrusion. Kept deliberately small and local: app-private
 * storage, capped length, no network, nothing that could itself become a leak.
 */
class ConnectionLog(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    enum class Event {
        PAIRED,
        UNPAIRED,
        CONNECTED,
        DISCONNECTED,
        REFUSED,
    }

    data class Entry(val timestamp: String, val event: String, val detail: String)

    fun record(event: Event, detail: String) {
        val line = "${timestampFormat.format(Date())}\t${event.name}\t${detail.sanitised()}"
        synchronized(lock) {
            runCatching {
                file.appendText("$line\n")
                trimIfNeeded()
            }.onFailure { Log.w(TAG, "could not write the connection log", it) }
        }
    }

    /** Most recent first. */
    fun recent(limit: Int = 50): List<Entry> = synchronized(lock) {
        runCatching {
            if (!file.exists()) return emptyList()
            file.readLines()
                .asReversed()
                .take(limit)
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) null else Entry(parts[0], parts[1], parts[2])
                }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    /** Device names come from the peer, so they must not be able to forge fields. */
    private fun String.sanitised(): String =
        replace('\t', ' ').replace('\n', ' ').take(120)

    private companion object {
        const val TAG = "ConnectionLog"
        const val FILE_NAME = "connections.log"
        const val MAX_LINES = 300
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
