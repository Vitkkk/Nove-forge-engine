package dev.novaforge.engine.core.runtime

class SignalBus {
    private val listeners = linkedMapOf<String, MutableList<(Any?) -> Unit>>()

    fun on(name: String, listener: (Any?) -> Unit): () -> Unit {
        listeners.getOrPut(name) { mutableListOf() } += listener
        return { listeners[name]?.remove(listener) }
    }

    fun emit(name: String, value: Any? = null) {
        listeners[name]?.toList()?.forEach { it(value) }
    }

    fun clear() = listeners.clear()
}
