package dev.novaforge.engine.editor

interface EditorCommand {
    val label: String
    fun apply()
    fun revert()
}

class UndoManager(private val maxHistory: Int = 200) {
    private val undo = ArrayDeque<EditorCommand>()
    private val redo = ArrayDeque<EditorCommand>()

    fun execute(command: EditorCommand) {
        command.apply()
        undo.addLast(command)
        if (undo.size > maxHistory) undo.removeFirst()
        redo.clear()
    }

    fun undo(): Boolean {
        val command = undo.removeLastOrNull() ?: return false
        command.revert()
        redo.addLast(command)
        return true
    }

    fun redo(): Boolean {
        val command = redo.removeLastOrNull() ?: return false
        command.apply()
        undo.addLast(command)
        return true
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
