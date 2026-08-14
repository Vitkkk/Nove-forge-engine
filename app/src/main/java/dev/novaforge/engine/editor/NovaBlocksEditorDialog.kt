package dev.novaforge.engine.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.novaforge.engine.core.blocks.BlockDefinition
import dev.novaforge.engine.core.blocks.BlockGraph
import dev.novaforge.engine.core.blocks.BlockNode
import dev.novaforge.engine.core.blocks.BlockRegistry
import dev.novaforge.engine.core.blocks.BlockScript

class NovaBlocksEditorDialog(
    private val context: Context,
    private val objectName: String,
    private val graph: BlockGraph,
    private val knownSignals: MutableSet<String> = linkedSetOf(),
    private val onSave: (BlockGraph) -> Unit
) {
    private val dialog = Dialog(context)
    private lateinit var workspace: LinearLayout
    private lateinit var title: TextView
    private var activeScript: BlockScript = graph.scripts.firstOrNull() ?: BlockScript("ready").also { graph.scripts += it }

    init { seedSignalsFromGraph() }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildRoot())
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        refreshWorkspace()
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun buildRoot(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(6, 47, 60))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(4, 57, 73))
            addView(iconButton("←") { dialog.dismiss() })
            title = TextView(context).apply {
                text = objectName
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, dp(8), 0)
            }
            addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(iconButton("Eventos") { chooseEvent() })
            addView(iconButton("＋") { showCategories() })
            addView(iconButton("✓") { onSave(graph); dialog.dismiss() })
        })
        addView(TextView(context).apply {
            text = "Toque em + para adicionar blocos • toque para editar • segure para excluir"
            textSize = 12f
            setTextColor(Color.rgb(190, 220, 226))
            setPadding(dp(14), dp(7), dp(14), dp(7))
        })
        workspace = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(90))
        }
        addView(ScrollView(context).apply { addView(workspace) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun chooseEvent() {
        val options = arrayOf(
            "Quando a cena começar",
            "Quando este objeto for criado",
            "A cada frame",
            "A cada physics frame",
            "Quando tocar neste objeto",
            "Quando receber sinal…",
            "Quando botão for pressionado"
        )
        val ids = arrayOf("ready", "created", "update", "fixed_update", "touch", "signal", "pressed")
        AlertDialog.Builder(context).setTitle("Evento").setItems(options) { _, index ->
            if (ids[index] == "signal") chooseSignal("Quando receber sinal") { selectOrCreateScript("signal", it) }
            else selectOrCreateScript(ids[index], null)
        }.show()
    }

    private fun selectOrCreateScript(event: String, argument: String?) {
        activeScript = graph.scripts.firstOrNull { it.event == event && it.argument == argument }
            ?: BlockScript(event, argument).also { graph.scripts += it }
        argument?.let(knownSignals::add)
        refreshWorkspace()
    }

    private fun chooseSignal(title: String, onSelected: (String) -> Unit) {
        seedSignalsFromGraph()
        val signals = knownSignals.filter { it.isNotBlank() }.distinct().sorted()
        val options = (signals + "+ Criar novo sinal").toTypedArray()
        AlertDialog.Builder(context).setTitle(title).setItems(options) { _, which ->
            if (which == signals.size) createSignal(onSelected) else onSelected(signals[which])
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun createSignal(onCreated: (String) -> Unit) {
        val input = EditText(context).apply { hint = "Nome do sinal" }
        AlertDialog.Builder(context).setTitle("Criar sinal").setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val value = input.text.toString().trim().ifBlank { "signal_${knownSignals.size + 1}" }
                knownSignals += value
                onCreated(value)
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun seedSignalsFromGraph() {
        fun visit(node: BlockNode) {
            if (node.type == "send_signal" || node.type == "send_signal_value") {
                node.fields["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let(knownSignals::add)
            }
            node.children.forEach(::visit)
            node.elseChildren.forEach(::visit)
        }
        graph.scripts.forEach { script ->
            if (script.event == "signal") script.argument?.trim()?.takeIf { it.isNotEmpty() }?.let(knownSignals::add)
            script.body.forEach(::visit)
        }
    }

    private fun showCategories() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 47, 60))
            setPadding(0, dp(8), 0, dp(12))
        }
        val search = EditText(context).apply {
            hint = "Buscar blocos"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        root.addView(search)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild(query: String = "") {
            list.removeAllViews()
            if (query.isNotBlank()) {
                BlockRegistry.definitions.filter { it.label.contains(query, true) || it.category.contains(query, true) }.forEach { def ->
                    list.addView(categoryRow(def.label, def.color.toInt()) { addDefinition(def) })
                }
            } else {
                BlockRegistry.categories.forEach { category ->
                    val defs = BlockRegistry.inCategory(category)
                    if (defs.isNotEmpty()) list.addView(categoryRow(categoryName(category), defs.first().color.toInt()) { showBlocksForCategory(category) })
                }
            }
        }
        search.addTextChangedListener(SimpleTextWatcher { rebuild(it) })
        rebuild()
        root.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    private fun showBlocksForCategory(category: String) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundColor(Color.rgb(6, 47, 60))
            addView(TextView(context).apply {
                text = "‹  ${categoryName(category)}"
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(18))
            })
        }
        BlockRegistry.inCategory(category).forEach { def -> root.addView(blockCard(def, preview = true) { addDefinition(def) }) }
        Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ScrollView(context).apply { addView(root) })
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    private fun addDefinition(def: BlockDefinition) {
        if (def.id.startsWith("event_")) {
            val event = when (def.id) {
                "event_ready" -> "ready"
                "event_created" -> "created"
                "event_update" -> "update"
                "event_fixed" -> "fixed_update"
                "event_touch" -> "touch"
                "event_signal" -> "signal"
                "event_pressed" -> "pressed"
                else -> "ready"
            }
            if (event == "signal") chooseSignal("Quando receber sinal") { selectOrCreateScript("signal", it) }
            else selectOrCreateScript(event, null)
            return
        }
        if (def.id == "send_signal" || def.id == "send_signal_value") {
            chooseSignal("Enviar qual sinal?") { signal ->
                val fields = LinkedHashMap(def.defaults)
                fields["name"] = signal
                activeScript.body += BlockNode(def.id, fields)
                knownSignals += signal
                refreshWorkspace()
            }
            return
        }
        activeScript.body += BlockNode(def.id, LinkedHashMap(def.defaults))
        refreshWorkspace()
    }

    private fun refreshWorkspace() {
        workspace.removeAllViews()
        val eventDef = when (activeScript.event) {
            "created" -> BlockRegistry.byId("event_created")
            "update" -> BlockRegistry.byId("event_update")
            "fixed_update" -> BlockRegistry.byId("event_fixed")
            "touch" -> BlockRegistry.byId("event_touch")
            "signal" -> BlockRegistry.byId("event_signal")
            "pressed" -> BlockRegistry.byId("event_pressed")
            else -> BlockRegistry.byId("event_ready")
        }
        eventDef?.let { def ->
            val label = if (activeScript.event == "signal") "Quando receber sinal [${activeScript.argument}]" else def.label
            val eventView = blockCard(def.copy(label = label), preview = false) {
                if (activeScript.event == "signal") chooseSignal("Quando receber sinal") { selectOrCreateScript("signal", it) }
            }
            workspace.addView(eventView)
        }
        activeScript.body.forEachIndexed { index, node ->
            val def = BlockRegistry.byId(node.type) ?: BlockDefinition(node.type, "Debug", node.type)
            workspace.addView(blockView(def, node, index))
        }
        if (activeScript.body.isEmpty()) {
            workspace.addView(TextView(context).apply {
                text = "\nToque em + e escolha uma categoria.\nOs blocos aparecerão empilhados aqui."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(190, 207, 213))
                setPadding(dp(20), dp(45), dp(20), dp(45))
            })
        }
    }

    private fun blockView(def: BlockDefinition, node: BlockNode, index: Int): View {
        val label = formatNodeLabel(def, node)
        return blockCard(def.copy(label = label), preview = false) { editNode(def, node, index) }.apply {
            setOnLongClickListener {
                AlertDialog.Builder(context).setTitle("Remover bloco?").setMessage(label)
                    .setPositiveButton("Remover") { _, _ -> activeScript.body.removeAt(index); refreshWorkspace() }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
    }

    private fun editNode(def: BlockDefinition, node: BlockNode, index: Int) {
        if (node.fields.isEmpty()) {
            AlertDialog.Builder(context).setTitle(def.label).setItems(arrayOf("Duplicar", "Excluir")) { _, which ->
                if (which == 0) activeScript.body.add(index + 1, BlockNode(node.type, LinkedHashMap(node.fields))) else activeScript.body.removeAt(index)
                refreshWorkspace()
            }.show()
            return
        }
        val keys = node.fields.keys.toList()
        val labels = keys.map { "$it = ${node.fields[it]}" } + listOf("Duplicar", "Excluir")
        AlertDialog.Builder(context).setTitle(def.label).setItems(labels.toTypedArray()) { _, which ->
            when {
                which < keys.size -> editField(keys[which], node)
                which == keys.size -> { activeScript.body.add(index + 1, BlockNode(node.type, LinkedHashMap(node.fields))); refreshWorkspace() }
                else -> { activeScript.body.removeAt(index); refreshWorkspace() }
            }
        }.show()
    }

    private fun editField(key: String, node: BlockNode) {
        val raw = node.fields[key] ?: "0"
        if (key == "name" && (node.type == "send_signal" || node.type == "send_signal_value")) {
            chooseSignal("Escolha o sinal") { signal -> node.fields[key] = signal; knownSignals += signal; refreshWorkspace() }
        } else if (key == "name" || key == "path") {
            val field = EditText(context).apply { setText(raw); selectAll() }
            AlertDialog.Builder(context).setTitle(key).setView(field)
                .setPositiveButton("OK") { _, _ -> node.fields[key] = field.text.toString(); refreshWorkspace() }
                .setNegativeButton("Cancelar", null).show()
        } else {
            ExpressionEditor(context, raw, graph) { result -> node.fields[key] = result; refreshWorkspace() }.show()
        }
    }

    private fun blockCard(def: BlockDefinition, preview: Boolean, click: () -> Unit): TextView = TextView(context).apply {
        text = def.label
        textSize = if (preview) 18f else 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(def.color.toInt()); cornerRadius = dp(8).toFloat(); setStroke(dp(2), darken(def.color.toInt()))
        }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun categoryRow(label: String, color: Int, click: () -> Unit): TextView = TextView(context).apply {
        text = label; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(22), dp(18), dp(22)); setBackgroundColor(color); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)).apply { setMargins(0, dp(1), 0, dp(1)) }
    }

    private fun formatNodeLabel(def: BlockDefinition, node: BlockNode): String {
        var result = def.label
        node.fields.forEach { (key, value) ->
            result = when {
                result.contains("()") -> result.replaceFirst("()", "[$value]")
                result.contains("<condição>") && key == "condition" -> result.replace("<condição>", "<$value>")
                result.contains("[nome]") && key == "name" -> result.replace("[nome]", "[$value]")
                else -> result
            }
        }
        return result
    }

    private fun iconButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { click() }; minWidth = dp(52)
    }

    private fun categoryName(category: String) = when (category) {
        "Events" -> "Evento"; "Control" -> "Controle"; "Movement" -> "Movimento"; "Looks" -> "Aparência"
        "Variables" -> "Dados"; "Signals" -> "Sinais"; "Input" -> "Dispositivo"; "Touch" -> "Toque"
        "Objects" -> "Objetos"; "Scenes" -> "Cenas"; "Camera" -> "Câmera"; "UI" -> "Interface"
        "Audio" -> "Som"; "Time" -> "Tempo"; "Strings" -> "Texto"; "Lists" -> "Listas"
        "Physics" -> "Física"; "Debug" -> "Debug"; "Functions" -> "Funções"; else -> category
    }

    private fun darken(color: Int): Int = Color.rgb((Color.red(color) * .68f).toInt(), (Color.green(color) * .68f).toInt(), (Color.blue(color) * .68f).toInt())
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

private class ExpressionEditor(
    private val context: Context,
    initial: String,
    private val graph: BlockGraph,
    private val onDone: (String) -> Unit
) {
    private val dialog = Dialog(context)
    private val editor = EditText(context).apply {
        setText(initial); textSize = 23f; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
        setPadding(12, 8, 12, 8); gravity = Gravity.CENTER_VERTICAL
    }
    private lateinit var extras: LinearLayout

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildUi())
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        editor.requestFocus(); editor.setSelection(editor.text.length)
    }

    private fun buildUi(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(6, 47, 60))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(Button(context).apply { text = "←"; setOnClickListener { dialog.dismiss() } })
            addView(TextView(context).apply { text = "Editor de expressão"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        })
        addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        extras = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(context).apply { addView(extras) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(HorizontalScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tab("Variáveis") { showVariableOptions() }); addView(tab("Propriedades") { showProperties() })
                addView(tab("Sensores") { showSensors() }); addView(tab("Funções") { showFunctions() })
                addView(tab("Lógica") { showLogic() }); addView(tab("Abc") { showKeyboard() })
            })
        })
        addView(numberPad())
    }

    private fun numberPad(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val rows = listOf(listOf("7", "8", "9", "÷", "×"), listOf("4", "5", "6", "-", "+"), listOf("1", "2", "3", "(", ")"), listOf("0", ".", "%", "⌫", "✓"))
        rows.forEach { row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { key ->
                    addView(Button(context).apply {
                        text = key; textSize = 20f
                        setOnClickListener {
                            when (key) {
                                "⌫" -> if (editor.selectionStart > 0) editor.text.delete(editor.selectionStart - 1, editor.selectionStart)
                                "✓" -> { onDone(editor.text.toString().ifBlank { "0" }); dialog.dismiss() }
                                "÷" -> insert("/"); "×" -> insert("*"); else -> insert(key)
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(58), 1f))
                }
            })
        }
    }

    private fun showVariableOptions() {
        showOptions("Variáveis", graph.variables.keys.map { "$it  →  var:$it" } + "+ Criar variável") { choice ->
            if (choice == "+ Criar variável") createVariable() else insert("var:${choice.substringBefore("  →")}")
        }
    }

    private fun createVariable() {
        val input = EditText(context).apply { hint = "Nome da variável" }
        AlertDialog.Builder(context).setTitle("Nova variável").setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "value" }
                graph.variables.putIfAbsent(name, "0"); insert("var:$name")
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showProperties() = showOptions("Propriedades deste objeto", listOf("self.x", "self.y", "self.rotation", "self.scaleX", "self.scaleY", "self.visible", "delta")) { insert(it) }
    private fun showSensors() = showOptions("Sensores", listOf("touch.x", "touch.y", "touch.count", "touch.pressed", "screen.width", "screen.height", "sensor.accelX", "sensor.accelY", "sensor.accelZ", "sensor.latitude", "sensor.longitude")) { insert(it) }
    private fun showFunctions() = showOptions("Funções", listOf("math.sin()", "math.cos()", "math.tan()", "math.sqrt()", "math.abs()", "math.floor()", "math.ceil()", "math.min()", "math.max()", "math.random()", "math.pi", "clamp()", "round()")) { if (it.endsWith("()")) insert(it.dropLast(1)) else insert(it) }
    private fun showLogic() = showOptions("Lógica", listOf(" == ", " ~= ", " > ", " < ", " >= ", " <= ", " and ", " or ", " not ", "true", "false")) { insert(it) }

    private fun showOptions(title: String, values: List<String>, click: (String) -> Unit) {
        extras.removeAllViews()
        extras.addView(TextView(context).apply { text = title; textSize = 19f; setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(16), dp(8)) })
        values.forEach { value ->
            extras.addView(TextView(context).apply {
                text = value; textSize = 18f; setTextColor(Color.rgb(205, 225, 230)); setPadding(dp(20), dp(13), dp(20), dp(13)); setOnClickListener { click(value) }
            })
        }
    }

    private fun tab(label: String, click: () -> Unit) = Button(context).apply { text = label; isAllCaps = false; setOnClickListener { click() } }

    private fun showKeyboard() {
        editor.inputType = android.text.InputType.TYPE_CLASS_TEXT
        editor.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun insert(value: String) {
        val start = editor.selectionStart.coerceAtLeast(0); val end = editor.selectionEnd.coerceAtLeast(start)
        editor.text.replace(start, end, value); editor.setSelection(start + value.length)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s?.toString().orEmpty())
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
