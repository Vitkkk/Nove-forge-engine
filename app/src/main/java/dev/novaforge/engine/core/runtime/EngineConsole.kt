package dev.novaforge.engine.core.runtime

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, WARNING, ERROR, LUA, ENGINE }

data class ConsoleEntry(
    val level: LogLevel,
    val message: String,
    val source: String? = null,
    val line: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun display(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val where = source?.let { " [$it${line?.let { n -> ":$n" } ?: ""}]" } ?: ""
        return "$time ${level.name}$where  $message"
    }
}

class EngineConsole {
    private val entries = ArrayDeque<ConsoleEntry>()
    private val listeners = mutableSetOf<(ConsoleEntry) -> Unit>()

    @Synchronized fun log(level: LogLevel, message: String, source: String? = null, line: Int? = null) {
        val entry = ConsoleEntry(level, message, source, line)
        entries.addLast(entry)
        while (entries.size > 500) entries.removeFirst()
        listeners.toList().forEach { it(entry) }
    }

    @Synchronized fun snapshot(): List<ConsoleEntry> = entries.toList()
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun addListener(listener: (ConsoleEntry) -> Unit) { listeners += listener }
    @Synchronized fun removeListener(listener: (ConsoleEntry) -> Unit) { listeners -= listener }
}
