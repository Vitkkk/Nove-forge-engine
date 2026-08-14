package dev.novaforge.engine

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import dev.novaforge.engine.core.blocks.BlockGraph
import dev.novaforge.engine.core.blocks.BlockNode
import dev.novaforge.engine.core.model.SceneCodec
import dev.novaforge.engine.core.model.SceneDocument
import dev.novaforge.engine.core.model.SceneNode
import dev.novaforge.engine.core.model.Transform2D
import dev.novaforge.engine.core.storage.ProjectStorage
import dev.novaforge.engine.editor.EditorCommand
import dev.novaforge.engine.editor.NovaBlocksEditorDialog
import dev.novaforge.engine.editor.NovaViewportView
import dev.novaforge.engine.editor.UndoManager
import java.util.UUID

class EditorActivity : AppCompatActivity() {
    private lateinit var storage: ProjectStorage
    private lateinit var project: DocumentFile
    private lateinit var scene: SceneDocument
    private lateinit var viewport: NovaViewportView
    private lateinit var consoleView: TextView
    private val undo = UndoManager()
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private var pendingSpriteNode: SceneNode? = null
    private lateinit var projectName: String

    private val spritePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val node = pendingSpriteNode
        pendingSpriteNode = null
        if (uri == null || node == null) return@registerForActivityResult
        runCatching { storage.importAsset(project, uri, "Sprites") }
            .onSuccess { path ->
                node.assetPath = path
                node.type = "Sprite"
                node.text = null
                dirty = true
                viewport.clearImageCache()
                viewport.selectNode(node)
                toast("Imagem importada para ${node.name}")
            }
            .onFailure { toast("Falha ao importar imagem: ${it.message}") }
    }

    private val autosave = object : Runnable {
        override fun run() {
            if (dirty) saveScene(silent = true)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = ProjectStorage(this)
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: return finish()
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
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dirty) saveScene(silent = true)
        super.onBackPressed()
    }

    private fun buildUi(projectName: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(11, 13, 18))
        addView(buildToolbar(projectName))
        viewport = NovaViewportView(context).apply {
            scene = this@EditorActivity.scene
            imageLoader = { path ->
                resolveProjectFile(path)?.let { file ->
                    runCatching { contentResolver.openInputStream(file.uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
                }
            }
            onSelectionChanged = { selected ->
                title = "NovaForge • $projectName${selected?.let { " • ${it.name}" } ?: ""}"
            }
            onNodeDragFinished = { node, fromX, fromY, toX, toY ->
                node.transform.x = fromX
                node.transform.y = fromY
                undo.execute(object : EditorCommand {
                    override val label = "Move ${node.name}"
                    override fun apply() {
                        node.transform.x = toX; node.transform.y = toY; viewport.invalidate(); dirty = true
                    }
                    override fun revert() {
                        node.transform.x = fromX; node.transform.y = fromY; viewport.invalidate(); dirty = true
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
            text = "Console pronto. O Play agora abre em uma tela separada."
        }
        addView(ScrollView(context).apply { addView(consoleView) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
    }

    private fun buildToolbar(projectName: String): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(toolButton("☰ Scene") { showSceneTree() })
            addView(toolButton("＋ Objeto") { addNodeDialog() })
            addView(toolButton("Inspector") { showInspector(viewport.selectedNode) })
            addView(toolButton("Lua") { showLuaEditor(viewport.selectedNode) })
            addView(toolButton("Blocks") { showBlocksEditor(viewport.selectedNode) })
            addView(toolButton("Snap") { viewport.toggleSnap() })
            addView(toolButton("Undo") { if (undo.undo()) { dirty = true; viewport.invalidate() } })
            addView(toolButton("Redo") { if (undo.redo()) { dirty = true; viewport.invalidate() } })
            addView(toolButton("Save") { saveScene() })
            addView(toolButton("▶ Play") { startPlayMode() })
            addView(TextView(context).apply { text = projectName; setTextColor(Color.WHITE); setPadding(dp(12), 0, dp(12), 0) })
        })
    }

    private fun showSceneTree() {
        val nodes = scene.root.walk().toList()
        val labels = nodes.map { node -> "  ".repeat(depthOf(scene.root, node.id).coerceAtLeast(0)) + "${node.name}  [${node.type}]" }
        AlertDialog.Builder(this).setTitle("Scene Tree").setItems(labels.toTypedArray()) { _, index ->
            viewport.selectNode(nodes[index]); showNodeActions(nodes[index])
        }.setNegativeButton("Fechar", null).show()
    }

    private fun showNodeActions(node: SceneNode) {
        if (node === scene.root) return
        val options = mutableListOf("Inspector", "Duplicate", "Delete", "Lua Script", "NovaBlocks")
        if (node.type == "Sprite" || node.type == "Node2D") options += "Importar/Trocar imagem"
        AlertDialog.Builder(this).setTitle(node.name).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Inspector" -> showInspector(node)
                "Duplicate" -> duplicateNode(node)
                "Delete" -> deleteNode(node)
                "Lua Script" -> showLuaEditor(node)
                "NovaBlocks" -> showBlocksEditor(node)
                "Importar/Trocar imagem" -> importSpriteFor(node)
            }
        }.show()
    }

    private fun addNodeDialog() {
        val labels = arrayOf("Sprite (importar imagem)", "Node2D", "Text", "Button", "TouchButton", "Camera2D", "AudioPlayer", "Timer", "Area2D")
        val types = arrayOf("Sprite", "Node2D", "Text", "Button", "TouchButton", "Camera2D", "AudioPlayer", "Timer", "Area2D")
        AlertDialog.Builder(this).setTitle("Adicionar objeto").setItems(labels) { _, which ->
            val type = types[which]
            val node = SceneNode(
                id = UUID.randomUUID().toString(), name = uniqueNodeName(type), type = type,
                transform = Transform2D(480f, 270f), width = if (type == "Camera2D") 1f else 180f,
                height = if (type == "Camera2D") 1f else if (type == "Sprite") 180f else 90f,
                text = if (type in listOf("Text", "Button", "TouchButton")) type else null
            )
            undo.execute(object : EditorCommand {
                override val label = "Add ${node.name}"
                override fun apply() { if (node !in scene.root.children) scene.root.children += node; viewport.selectNode(node); dirty = true }
                override fun revert() { scene.root.children.remove(node); viewport.selectNode(null); dirty = true }
            })
            viewport.invalidate()
            if (type == "Sprite") importSpriteFor(node)
        }.show()
    }

    private fun importSpriteFor(node: SceneNode) {
        pendingSpriteNode = node
        spritePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
    }

    private fun showInspector(node: SceneNode?) {
        if (node == null) { toast("Selecione um objeto primeiro"); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val name = field(form, "Name", node.name)
        val x = field(form, "Position X", node.transform.x.toString())
        val y = field(form, "Position Y", node.transform.y.toString())
        val rotation = field(form, "Rotation", node.transform.rotation.toString())
        val scaleX = field(form, "Scale X", node.transform.scaleX.toString())
        val scaleY = field(form, "Scale Y", node.transform.scaleY.toString())
        val width = field(form, "Width", node.width.toString())
        val height = field(form, "Height", node.height.toString())
        val tags = field(form, "Tags (comma-separated)", node.tags.joinToString(","))
        if (node.assetPath != null) {
            form.addView(TextView(this).apply { text = "Imagem: ${node.assetPath}"; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, dp(8)) })
            form.addView(Button(this).apply { text = "Trocar imagem"; setOnClickListener { importSpriteFor(node) } })
        }
        AlertDialog.Builder(this).setTitle("Inspector • ${node.type}").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Apply") { _, _ ->
                val before = snapshot(node)
                val after = before.copy(
                    name = name.text.toString().ifBlank { node.name }, x = x.floatOr(node.transform.x), y = y.floatOr(node.transform.y),
                    rotation = rotation.floatOr(node.transform.rotation), scaleX = scaleX.floatOr(node.transform.scaleX), scaleY = scaleY.floatOr(node.transform.scaleY),
                    width = width.floatOr(node.width), height = height.floatOr(node.height),
                    tags = tags.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                )
                undo.execute(snapshotCommand(node, before, after)); viewport.invalidate()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showLuaEditor(node: SceneNode?) {
        if (node == null) { toast("Selecione um objeto primeiro"); return }
        val path = node.scriptPath ?: "Scripts/${safeFileName(node.name)}.lua"
        val source = runCatching { storage.readText(project, path) }.getOrElse { DEFAULT_LUA }
        val editor = EditText(this).apply {
            setText(source); gravity = Gravity.TOP or Gravity.START; typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(15, 17, 23)); minLines = 18
            setPadding(dp(12), dp(12), dp(12), dp(12)); setHorizontallyScrolling(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(symbolBar(editor)); addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)))
        }
        AlertDialog.Builder(this).setTitle(path).setView(layout).setPositiveButton("Save") { _, _ ->
            runCatching { storage.writeText(project, path, editor.text.toString()); node.scriptPath = path; dirty = true }
                .onSuccess { toast("Lua salvo") }.onFailure { toast(it.message ?: "Erro ao salvar") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showBlocksEditor(node: SceneNode?) {
        if (node == null) { toast("Selecione um objeto primeiro"); return }
        val path = node.blocksPath ?: "Blocks/${safeFileName(node.name)}.blocks"
        val graph = runCatching { BlockGraph.fromJson(storage.readText(project, path)) }.getOrElse { BlockGraph() }
        val knownSignals = collectProjectSignals(graph)
        NovaBlocksEditorDialog(this, node.name, graph, knownSignals) { edited ->
            runCatching {
                storage.writeText(project, path, edited.toJson())
                node.blocksPath = path
                dirty = true
            }.onSuccess { toast("NovaBlocks salvo") }.onFailure { toast("Erro ao salvar blocos: ${it.message}") }
        }.show()
    }

    private fun collectProjectSignals(current: BlockGraph): MutableSet<String> {
        val result = linkedSetOf<String>()
        fun collectNode(node: BlockNode) {
            if (node.type == "send_signal" || node.type == "send_signal_value") {
                node.fields["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add)
            }
            node.children.forEach(::collectNode)
            node.elseChildren.forEach(::collectNode)
        }
        fun collectGraph(graph: BlockGraph) {
            graph.scripts.forEach { script ->
                if (script.event == "signal") script.argument?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add)
                script.body.forEach(::collectNode)
            }
        }
        collectGraph(current)
        scene.root.walk().mapNotNull { it.blocksPath }.distinct().forEach { blocksPath ->
            runCatching { BlockGraph.fromJson(storage.readText(project, blocksPath)) }.getOrNull()?.let(::collectGraph)
        }
        return result
    }

    private fun startPlayMode() {
        saveScene(silent = true)
        startActivity(Intent(this, PlayActivity::class.java).putExtra(PlayActivity.EXTRA_PROJECT_NAME, projectName))
    }

    private fun saveScene(silent: Boolean = false) {
        runCatching { storage.saveMainScene(project, SceneCodec.encode(scene)); dirty = false }
            .onSuccess { if (!silent) toast("Cena salva") }
            .onFailure { toast("Erro ao salvar: ${it.message}") }
    }

    private fun duplicateNode(node: SceneNode) {
        val copy = cloneNode(node).apply { name = uniqueNodeName(node.name); transform.x += 32f; transform.y += 32f }
        scene.root.children += copy; viewport.selectNode(copy); dirty = true; viewport.invalidate()
    }

    private fun cloneNode(node: SceneNode): SceneNode = SceneNode(
        id = UUID.randomUUID().toString(), name = node.name, type = node.type, transform = node.transform.copy(), width = node.width, height = node.height,
        text = node.text, assetPath = node.assetPath, scriptPath = node.scriptPath, blocksPath = node.blocksPath, color = node.color, enabled = node.enabled,
        tags = node.tags.toMutableSet(), properties = node.properties.toMutableMap(), children = node.children.map(::cloneNode).toMutableList()
    )

    private fun deleteNode(node: SceneNode) {
        if (removeNode(scene.root, node.id)) { viewport.selectNode(null); dirty = true; viewport.invalidate() }
    }

    private fun removeNode(parent: SceneNode, id: String): Boolean {
        val direct = parent.children.firstOrNull { it.id == id }
        if (direct != null) return parent.children.remove(direct)
        return parent.children.any { removeNode(it, id) }
    }

    private fun resolveProjectFile(relativePath: String): DocumentFile? {
        val segments = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        var current = project
        for (segment in segments) current = current.findFile(segment) ?: return null
        return current
    }

    private fun depthOf(root: SceneNode, id: String, depth: Int = 0): Int {
        if (root.id == id) return depth
        root.children.forEach { child -> val result = depthOf(child, id, depth + 1); if (result >= 0) return result }
        return -1
    }

    private fun uniqueNodeName(base: String): String {
        val names = scene.root.walk().map { it.name }.toSet()
        if (base !in names) return base
        var i = 2; while ("$base$i" in names) i++; return "$base$i"
    }

    private data class NodeSnapshot(
        val name: String, val x: Float, val y: Float, val rotation: Float, val scaleX: Float, val scaleY: Float,
        val width: Float, val height: Float, val tags: Set<String>
    )

    private fun snapshot(node: SceneNode) = NodeSnapshot(
        node.name, node.transform.x, node.transform.y, node.transform.rotation, node.transform.scaleX, node.transform.scaleY,
        node.width, node.height, node.tags.toSet()
    )

    private fun applySnapshot(node: SceneNode, value: NodeSnapshot) {
        node.name = value.name; node.transform.x = value.x; node.transform.y = value.y; node.transform.rotation = value.rotation
        node.transform.scaleX = value.scaleX; node.transform.scaleY = value.scaleY; node.width = value.width; node.height = value.height
        node.tags.clear(); node.tags.addAll(value.tags); dirty = true; viewport.invalidate()
    }

    private fun snapshotCommand(node: SceneNode, before: NodeSnapshot, after: NodeSnapshot) = object : EditorCommand {
        override val label = "Edit ${node.name}"
        override fun apply() = applySnapshot(node, after)
        override fun revert() = applySnapshot(node, before)
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText = EditText(this).apply { hint = label; setText(value); parent.addView(this) }
    private fun EditText.floatOr(default: Float): Float = text.toString().toFloatOrNull() ?: default

    private fun symbolBar(editor: EditText): HorizontalScrollView = HorizontalScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("(", ")", "[", "]", "{", "}", "=", "+", "-", "*", "/", ".", ",", "\"", "'", ":").forEach { symbol ->
                addView(Button(context).apply { text = symbol; minWidth = dp(42); setOnClickListener { editor.text.insert(editor.selectionStart.coerceAtLeast(0), symbol) } })
            }
        })
    }

    private fun toolButton(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun safeFileName(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
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
