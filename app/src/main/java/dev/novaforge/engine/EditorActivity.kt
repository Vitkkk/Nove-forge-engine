package dev.novaforge.engine

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import dev.novaforge.engine.core.blocks.BlockGraph
import dev.novaforge.engine.core.blocks.BlockNode
import dev.novaforge.engine.core.blocks.BlockScript
import dev.novaforge.engine.core.model.SceneCodec
import dev.novaforge.engine.core.model.SceneDocument
import dev.novaforge.engine.core.model.SceneNode
import dev.novaforge.engine.core.model.Transform2D
import dev.novaforge.engine.core.runtime.EngineRuntime
import dev.novaforge.engine.core.storage.ProjectStorage
import dev.novaforge.engine.editor.EditorCommand
import dev.novaforge.engine.editor.NovaViewportView
import dev.novaforge.engine.editor.UndoManager
import java.util.UUID

class EditorActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var storage: ProjectStorage
    private lateinit var project: DocumentFile
    private lateinit var scene: SceneDocument
    private lateinit var viewport: NovaViewportView
    private lateinit var consoleView: TextView
    private val undo = UndoManager()
    private val handler = Handler(Looper.getMainLooper())
    private var runtime: EngineRuntime? = null
    private var lastFrameNanos = 0L
    private var dirty = false
    private val autosave = object : Runnable {
        override fun run() {
            if (dirty && runtime == null) saveScene(silent = true)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = ProjectStorage(this)
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: return finish()
        runCatching {
            project = storage.projectByName(projectName)
            scene = storage.loadMainScene(project)
        }.onFailure {
            toast("Falha ao abrir projeto: ${it.message}")
            finish()
            return
        }
        setContentView(buildUi(projectName))
        handler.postDelayed(autosave, 30_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autosave)
        stopPlayMode()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (runtime != null) {
            stopPlayMode()
            return
        }
        if (dirty) saveScene(silent = true)
        super.onBackPressed()
    }

    private fun buildUi(projectName: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(11, 13, 18))
        addView(buildToolbar(projectName))
        viewport = NovaViewportView(context).apply {
            scene = this@EditorActivity.scene
            onSelectionChanged = { selected ->
                title = "NovaForge • $projectName${selected?.let { " • ${it.name}" } ?: ""}"
            }
            onNodeDragFinished = { node, fromX, fromY, toX, toY ->
                node.transform.x = fromX
                node.transform.y = fromY
                undo.execute(object : EditorCommand {
                    override val label = "Move ${node.name}"
                    override fun apply() {
                        node.transform.x = toX
                        node.transform.y = toY
                        viewport.invalidate()
                        dirty = true
                    }
                    override fun revert() {
                        node.transform.x = fromX
                        node.transform.y = fromY
                        viewport.invalidate()
                        dirty = true
                    }
                })
            }
        }
        addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        consoleView = TextView(context).apply {
            setBackgroundColor(Color.rgb(8, 9, 13))
            setTextColor(Color.LTGRAY)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            text = "Console pronto."
        }
        addView(
            ScrollView(context).apply { addView(consoleView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
        )
    }

    private fun buildToolbar(projectName: String): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(toolButton("☰ Scene") { showSceneTree() })
            addView(toolButton("＋ Node") { addNodeDialog() })
            addView(toolButton("Inspector") { showInspector(viewport.selectedNode) })
            addView(toolButton("Lua") { showLuaEditor(viewport.selectedNode) })
            addView(toolButton("Blocks") { showBlocksEditor(viewport.selectedNode) })
            addView(toolButton("Undo") { if (undo.undo()) { dirty = true; viewport.invalidate() } })
            addView(toolButton("Redo") { if (undo.redo()) { dirty = true; viewport.invalidate() } })
            addView(toolButton("Save") { saveScene() })
            addView(toolButton("▶ Play") { startPlayMode() })
            addView(toolButton("■ Stop") { stopPlayMode() })
            addView(TextView(context).apply {
                text = projectName
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, dp(12), 0)
            })
        })
    }

    private fun showSceneTree() {
        val nodes = scene.root.walk().toList()
        val labels = nodes.map { node ->
            val depth = depthOf(scene.root, node.id)
            "  ".repeat(depth.coerceAtLeast(0)) + "${node.name}  [${node.type}]"
        }
        AlertDialog.Builder(this)
            .setTitle("Scene Tree")
            .setItems(labels.toTypedArray()) { _, index ->
                viewport.selectNode(nodes[index])
                showNodeActions(nodes[index])
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showNodeActions(node: SceneNode) {
        if (node === scene.root) return
        AlertDialog.Builder(this)
            .setTitle(node.name)
            .setItems(arrayOf("Inspector", "Duplicate", "Delete", "Lua Script", "NovaBlocks")) { _, which ->
                when (which) {
                    0 -> showInspector(node)
                    1 -> duplicateNode(node)
                    2 -> deleteNode(node)
                    3 -> showLuaEditor(node)
                    4 -> showBlocksEditor(node)
                }
            }.show()
    }

    private fun addNodeDialog() {
        val types = arrayOf("Node2D", "Text", "Button", "TouchButton", "Camera2D", "AudioPlayer", "Timer", "Area2D")
        AlertDialog.Builder(this).setTitle("Adicionar objeto").setItems(types) { _, which ->
            val type = types[which]
            val node = SceneNode(
                id = UUID.randomUUID().toString(),
                name = uniqueNodeName(type),
                type = type,
                transform = Transform2D(480f, 270f),
                width = if (type == "Camera2D") 1f else 180f,
                height = if (type == "Camera2D") 1f else 90f,
                text = if (type in listOf("Text", "Button", "TouchButton")) type else null
            )
            undo.execute(object : EditorCommand {
                override val label = "Add ${node.name}"
                override fun apply() {
                    if (node !in scene.root.children) scene.root.children += node
                    viewport.selectNode(node)
                    dirty = true
                }
                override fun revert() {
                    scene.root.children.remove(node)
                    viewport.selectNode(null)
                    dirty = true
                }
            })
            viewport.invalidate()
        }.show()
    }

    private fun showInspector(node: SceneNode?) {
        if (node == null) {
            toast("Selecione um objeto primeiro")
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val name = field(form, "Name", node.name)
        val x = field(form, "Position X", node.transform.x.toString())
        val y = field(form, "Position Y", node.transform.y.toString())
        val rotation = field(form, "Rotation", node.transform.rotation.toString())
        val scaleX = field(form, "Scale X", node.transform.scaleX.toString())
        val scaleY = field(form, "Scale Y", node.transform.scaleY.toString())
        val width = field(form, "Width", node.width.toString())
        val height = field(form, "Height", node.height.toString())
        val tags = field(form, "Tags (comma-separated)", node.tags.joinToString(","))
        AlertDialog.Builder(this)
            .setTitle("Inspector • ${node.type}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Apply") { _, _ ->
                val before = snapshot(node)
                val after = before.copy(
                    name = name.text.toString().ifBlank { node.name },
                    x = x.floatOr(node.transform.x),
                    y = y.floatOr(node.transform.y),
                    rotation = rotation.floatOr(node.transform.rotation),
                    scaleX = scaleX.floatOr(node.transform.scaleX),
                    scaleY = scaleY.floatOr(node.transform.scaleY),
                    width = width.floatOr(node.width),
                    height = height.floatOr(node.height),
                    tags = tags.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                )
                undo.execute(snapshotCommand(node, before, after))
                viewport.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLuaEditor(node: SceneNode?) {
        if (node == null) {
            toast("Selecione um objeto primeiro")
            return
        }
        val path = node.scriptPath ?: "Scripts/${safeFileName(node.name)}.lua"
        val source = runCatching { storage.readText(project, path) }.getOrElse { DEFAULT_LUA }
        val editor = EditText(this).apply {
            setText(source)
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(15, 17, 23))
            minLines = 18
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setHorizontallyScrolling(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(symbolBar(editor))
            addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)))
        }
        AlertDialog.Builder(this)
            .setTitle(path)
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    storage.writeText(project, path, editor.text.toString())
                    node.scriptPath = path
                    dirty = true
                }.onSuccess { toast("Lua salvo") }
                    .onFailure { toast(it.message ?: "Erro ao salvar") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBlocksEditor(node: SceneNode?) {
        if (node == null) {
            toast("Selecione um objeto primeiro")
            return
        }
        val path = node.blocksPath ?: "Blocks/${safeFileName(node.name)}.blocks"
        val graph = runCatching { BlockGraph.fromJson(storage.readText(project, path)) }.getOrElse { BlockGraph() }
        val eventSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("ready", "update", "fixed_update", "touch", "signal"))
        }
        val signalField = EditText(this).apply {
            hint = "Signal name (for signal event)"
            setText("move_right")
        }
        val actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("move_x", "move_y", "set_x", "set_y", "rotate", "send_signal", "print", "destroy_self"))
        }
        val valueField = EditText(this).apply {
            hint = "Expression/value"
            setText("2")
        }
        val actionList = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = renderBlocks(graph)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(TextView(context).apply { text = "Event"; setTextColor(Color.LTGRAY) })
            addView(eventSpinner)
            addView(signalField)
            addView(TextView(context).apply { text = "Add block"; setTextColor(Color.LTGRAY) })
            addView(actionSpinner)
            addView(valueField)
            addView(Button(context).apply {
                text = "Add to event"
                setOnClickListener {
                    val event = eventSpinner.selectedItem.toString()
                    val signal = signalField.text.toString()
                    val script = graph.scripts.firstOrNull { it.event == event && (event != "signal" || it.argument == signal) }
                        ?: BlockScript(event, if (event == "signal") signal else null).also { graph.scripts += it }
                    val type = actionSpinner.selectedItem.toString()
                    val fields = linkedMapOf<String, String>()
                    when (type) {
                        "send_signal" -> {
                            fields["name"] = signal.ifBlank { "signal" }
                            fields["value"] = valueField.text.toString().ifBlank { "nil" }
                        }
                        "print" -> {
                            val raw = valueField.text.toString()
                            fields["value"] = if (raw.startsWith('"')) raw else "\"$raw\""
                        }
                        "destroy_self" -> Unit
                        else -> fields["value"] = valueField.text.toString().ifBlank { "0" }
                    }
                    script.body += BlockNode(type, fields)
                    actionList.text = renderBlocks(graph)
                }
            })
            addView(Button(context).apply {
                text = "Clear graph"
                setOnClickListener {
                    graph.scripts.clear()
                    actionList.text = renderBlocks(graph)
                }
            })
            addView(actionList)
        }
        AlertDialog.Builder(this)
            .setTitle("NovaBlocks • ${node.name}")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    storage.writeText(project, path, graph.toJson())
                    node.blocksPath = path
                    dirty = true
                }.onSuccess { toast("NovaBlocks salvo") }
                    .onFailure { toast(it.message ?: "Erro") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startPlayMode() {
        if (runtime != null) return
        saveScene(silent = true)
        val playScene = SceneCodec.deepCopy(scene)
        runtime = EngineRuntime(
            scene = playScene,
            scriptLoader = { path -> runCatching { storage.readText(project, path) }.getOrNull() },
            blocksLoader = { path -> runCatching { storage.readText(project, path) }.getOrNull() }
        ).also { engine ->
            engine.console.addListener { entry -> runOnUiThread { appendConsole(entry.display()) } }
            engine.start()
        }
        viewport.runtime = runtime
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
        viewport.invalidate()
    }

    private fun stopPlayMode() {
        Choreographer.getInstance().removeFrameCallback(this)
        runtime?.stop()
        runtime = null
        viewport.runtime = null
        lastFrameNanos = 0L
        if (::viewport.isInitialized) viewport.invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        val engine = runtime ?: return
        if (lastFrameNanos != 0L) {
            val delta = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
            engine.frame(delta)
        }
        lastFrameNanos = frameTimeNanos
        viewport.frame()
        if (engine.requestStop) {
            stopPlayMode()
            return
        }
        if (engine.requestReload) {
            stopPlayMode()
            startPlayMode()
            return
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun saveScene(silent: Boolean = false) {
        if (runtime != null) return
        runCatching {
            storage.saveMainScene(project, SceneCodec.encode(scene))
            dirty = false
        }.onSuccess {
            if (!silent) toast("Cena salva")
        }.onFailure {
            toast("Erro ao salvar: ${it.message}")
        }
    }

    private fun duplicateNode(node: SceneNode) {
        val copy = cloneNode(node).apply {
            name = uniqueNodeName(node.name)
            transform.x += 32f
            transform.y += 32f
        }
        scene.root.children += copy
        viewport.selectNode(copy)
        dirty = true
        viewport.invalidate()
    }

    private fun cloneNode(node: SceneNode): SceneNode = SceneNode(
        id = UUID.randomUUID().toString(),
        name = node.name,
        type = node.type,
        transform = node.transform.copy(),
        width = node.width,
        height = node.height,
        text = node.text,
        assetPath = node.assetPath,
        scriptPath = node.scriptPath,
        blocksPath = node.blocksPath,
        color = node.color,
        enabled = node.enabled,
        tags = node.tags.toMutableSet(),
        properties = node.properties.toMutableMap(),
        children = node.children.map(::cloneNode).toMutableList()
    )

    private fun deleteNode(node: SceneNode) {
        if (removeNode(scene.root, node.id)) {
            viewport.selectNode(null)
            dirty = true
            viewport.invalidate()
        }
    }

    private fun removeNode(parent: SceneNode, id: String): Boolean {
        val direct = parent.children.firstOrNull { it.id == id }
        if (direct != null) return parent.children.remove(direct)
        return parent.children.any { removeNode(it, id) }
    }

    private fun depthOf(root: SceneNode, id: String, depth: Int = 0): Int {
        if (root.id == id) return depth
        root.children.forEach { child ->
            val result = depthOf(child, id, depth + 1)
            if (result >= 0) return result
        }
        return -1
    }

    private fun renderBlocks(graph: BlockGraph): String = buildString {
        if (graph.scripts.isEmpty()) append("No blocks yet")
        graph.scripts.forEach { script ->
            append("\nWHEN ${script.event}${script.argument?.let { " [$it]" } ?: ""}\n")
            script.body.forEach { block -> append("  • ${block.type} ${block.fields}\n") }
        }
    }

    private fun uniqueNodeName(base: String): String {
        val names = scene.root.walk().map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base$i" in names) i++
        return "$base$i"
    }

    private data class NodeSnapshot(
        val name: String,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val scaleX: Float,
        val scaleY: Float,
        val width: Float,
        val height: Float,
        val tags: Set<String>
    )

    private fun snapshot(node: SceneNode) = NodeSnapshot(
        node.name,
        node.transform.x,
        node.transform.y,
        node.transform.rotation,
        node.transform.scaleX,
        node.transform.scaleY,
        node.width,
        node.height,
        node.tags.toSet()
    )

    private fun applySnapshot(node: SceneNode, value: NodeSnapshot) {
        node.name = value.name
        node.transform.x = value.x
        node.transform.y = value.y
        node.transform.rotation = value.rotation
        node.transform.scaleX = value.scaleX
        node.transform.scaleY = value.scaleY
        node.width = value.width
        node.height = value.height
        node.tags.clear()
        node.tags.addAll(value.tags)
        dirty = true
        viewport.invalidate()
    }

    private fun snapshotCommand(node: SceneNode, before: NodeSnapshot, after: NodeSnapshot) = object : EditorCommand {
        override val label = "Edit ${node.name}"
        override fun apply() = applySnapshot(node, after)
        override fun revert() = applySnapshot(node, before)
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText = EditText(this).apply {
        hint = label
        setText(value)
        parent.addView(this)
    }

    private fun EditText.floatOr(default: Float): Float = text.toString().toFloatOrNull() ?: default

    private fun symbolBar(editor: EditText): HorizontalScrollView = HorizontalScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("(", ")", "[", "]", "{", "}", "=", "+", "-", "*", "/", ".", ",", "\"", "'", ":").forEach { symbol ->
                addView(Button(context).apply {
                    text = symbol
                    minWidth = dp(42)
                    setOnClickListener {
                        val start = editor.selectionStart.coerceAtLeast(0)
                        editor.text.insert(start, symbol)
                    }
                })
            }
        })
    }

    private fun toolButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun safeFileName(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun appendConsole(line: String) { consoleView.append("\n$line") }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_NAME = "project_name"
        private const val DEFAULT_LUA = """function ready()
    NovaForge.print("ready")
end

function update(delta)
end
"""
    }
}
