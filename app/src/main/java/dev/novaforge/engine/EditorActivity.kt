package dev.novaforge.engine

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
import dev.novaforge.engine.editor.LuaCodeEditorDialog
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
    private lateinit var selectionLabel: TextView
    private lateinit var saveState: TextView
    private val undo = UndoManager()
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
        set(value) { field = value; if (::saveState.isInitialized) saveState.text = if (value) "● Alterações não salvas" else "✓ Salvo" }
    private var pendingSpriteNode: SceneNode? = null
    private lateinit var projectName: String

    private val spritePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val node = pendingSpriteNode
        pendingSpriteNode = null
        if (uri == null || node == null) return@registerForActivityResult
        runCatching { storage.importAsset(project, uri, "Sprites") }
            .onSuccess { path ->
                node.assetPath = path; node.type = "Sprite"; node.text = null; dirty = true
                viewport.clearImageCache(); viewport.selectNode(node); toast("Imagem importada para ${node.name}")
            }.onFailure { toast("Falha ao importar imagem: ${it.message}") }
    }

    private val autosave = object : Runnable {
        override fun run() { if (dirty) saveScene(silent = true); handler.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = ProjectStorage(this)
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: return finish()
        runCatching { project = storage.projectByName(projectName); scene = storage.loadMainScene(project) }
            .onFailure { toast("Falha ao abrir projeto: ${it.message}"); finish(); return }
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        handler.postDelayed(autosave, 30_000)
    }

    override fun onDestroy() { handler.removeCallbacks(autosave); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (dirty) saveScene(silent = true); super.onBackPressed() }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        addView(buildProjectHeader())
        addView(buildToolbar())

        viewport = NovaViewportView(context).apply {
            scene = this@EditorActivity.scene
            imageLoader = { path -> resolveProjectFile(path)?.let { file -> runCatching { contentResolver.openInputStream(file.uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }
            onSelectionChanged = { selected ->
                selectionLabel.text = selected?.let { "${it.name}  •  ${it.type}" } ?: "Nenhum objeto selecionado"
                title = "NovaForge • $projectName${selected?.let { " • ${it.name}" } ?: ""}"
            }
            onNodeDragFinished = { node, fromX, fromY, toX, toY ->
                node.transform.x = fromX; node.transform.y = fromY
                undo.execute(object : EditorCommand {
                    override val label = "Mover ${node.name}"
                    override fun apply() { node.transform.x = toX; node.transform.y = toY; invalidate(); dirty = true }
                    override fun revert() { node.transform.x = fromX; node.transform.y = fromY; invalidate(); dirty = true }
                })
            }
        }
        addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(buildSelectionBar())
        addView(buildConsole())
    }

    private fun buildProjectHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(12), dp(8)); setBackgroundColor(SURFACE)
        addView(TextView(context).apply {
            text = "N"; gravity = Gravity.CENTER; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = rounded(ACCENT, 10)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            addView(TextView(context).apply { text = projectName; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TEXT) })
            addView(TextView(context).apply { text = "Main.scene  •  2D Workspace"; textSize = 10f; setTextColor(MUTED) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveState = TextView(context).apply { text = "✓ Salvo"; textSize = 10f; setTextColor(Color.rgb(113, 210, 154)); setPadding(dp(8), dp(5), dp(8), dp(5)) }
        addView(saveState)
        addView(smallButton("▶", ACCENT) { startPlayMode() })
    }

    private fun buildToolbar(): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; setBackgroundColor(SURFACE_2)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(7), dp(6), dp(7), dp(6))
            addView(toolButton("☰  Scene") { showSceneTree() })
            addView(toolButton("＋  Create") { addNodeDialog() })
            addView(toolButton("◇  Inspector") { showInspector(viewport.selectedNode) })
            addView(toolButton("</>  Code") { showLuaEditor(viewport.selectedNode) })
            addView(toolButton("▦  Blocks") { showBlocksEditor(viewport.selectedNode) })
            addView(toolButton("#  Snap") { viewport.toggleSnap() })
            addView(toolButton("↶") { if (undo.undo()) { dirty = true; viewport.invalidate() } })
            addView(toolButton("↷") { if (undo.redo()) { dirty = true; viewport.invalidate() } })
            addView(toolButton("Save") { saveScene() })
        })
    }

    private fun buildSelectionBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(Color.rgb(15, 18, 26))
        selectionLabel = TextView(context).apply { text = "Nenhum objeto selecionado"; textSize = 11f; setTextColor(MUTED) }
        addView(selectionLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply { text = "Pinch: zoom  •  2 dedos: pan"; textSize = 10f; setTextColor(Color.rgb(98, 110, 130)) })
    }

    private fun buildConsole(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8, 10, 15))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(TextView(context).apply { text = "CONSOLE"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ACCENT_LIGHT) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = "INFO  •  ENGINE  •  LUA"; textSize = 9f; setTextColor(MUTED) })
        })
        consoleView = TextView(context).apply {
            setTextColor(Color.rgb(169, 178, 195)); typeface = Typeface.MONOSPACE; textSize = 10f
            setPadding(dp(10), dp(4), dp(10), dp(8)); text = "[ENGINE] Workspace pronto. Pressione ▶ para executar em Play Mode."
        }
        addView(ScrollView(context).apply { addView(consoleView) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
    }

    private fun showSceneTree() {
        val nodes = scene.root.walk().toList()
        val labels = nodes.map { node -> "  ".repeat(depthOf(scene.root, node.id).coerceAtLeast(0)) + nodeIcon(node.type) + "  ${node.name}    ${node.type}" }
        AlertDialog.Builder(this).setTitle("Scene Tree").setItems(labels.toTypedArray()) { _, index -> viewport.selectNode(nodes[index]); showNodeActions(nodes[index]) }
            .setNegativeButton("Fechar", null).show()
    }

    private fun nodeIcon(type: String) = when (type) { "Camera2D" -> "◉"; "Sprite", "Image" -> "▣"; "Button", "TouchButton" -> "▭"; "AudioPlayer" -> "♫"; "Timer" -> "◷"; else -> "◇" }

    private fun showNodeActions(node: SceneNode) {
        if (node === scene.root) return
        val options = mutableListOf("Inspector", "Duplicar", "Excluir", "Editar Lua", "NovaBlocks")
        if (node.type == "Sprite" || node.type == "Image" || node.type == "Node2D") options += "Importar/Trocar imagem"
        AlertDialog.Builder(this).setTitle("${node.name}  •  ${node.type}").setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Inspector" -> showInspector(node); "Duplicar" -> duplicateNode(node); "Excluir" -> deleteNode(node)
                "Editar Lua" -> showLuaEditor(node); "NovaBlocks" -> showBlocksEditor(node); "Importar/Trocar imagem" -> importSpriteFor(node)
            }
        }.show()
    }

    private fun addNodeDialog() {
        val labels = arrayOf(
            "Sprite  •  imagem importada", "Node2D  •  objeto base", "Camera2D", "Text / Label", "Button", "TouchButton",
            "Image  •  UI", "Panel  •  UI", "ProgressBar  •  UI", "Slider  •  UI", "CanvasUI", "AudioPlayer", "Timer", "Area2D"
        )
        val types = arrayOf("Sprite", "Node2D", "Camera2D", "Text", "Button", "TouchButton", "Image", "Panel", "ProgressBar", "Slider", "CanvasUI", "AudioPlayer", "Timer", "Area2D")
        AlertDialog.Builder(this).setTitle("Create object").setMessage("Escolha um tipo de node para adicionar à cena.").setItems(labels) { _, which ->
            val type = types[which]
            val node = SceneNode(
                id = UUID.randomUUID().toString(), name = uniqueNodeName(type), type = type, transform = Transform2D(480f, 270f),
                width = if (type == "Camera2D") 1f else if (type in listOf("Panel", "CanvasUI")) 320f else 180f,
                height = if (type == "Camera2D") 1f else if (type in listOf("Panel", "CanvasUI")) 180f else if (type in listOf("Sprite", "Image")) 180f else 90f,
                text = if (type in listOf("Text", "Button", "TouchButton")) type else null
            )
            undo.execute(object : EditorCommand {
                override val label = "Adicionar ${node.name}"
                override fun apply() { if (node !in scene.root.children) scene.root.children += node; viewport.selectNode(node); dirty = true }
                override fun revert() { scene.root.children.remove(node); viewport.selectNode(null); dirty = true }
            })
            viewport.invalidate(); if (type == "Sprite" || type == "Image") importSpriteFor(node)
        }.show()
    }

    private fun importSpriteFor(node: SceneNode) { pendingSpriteNode = node; spritePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }

    private fun showInspector(node: SceneNode?) {
        if (node == null) { toast("Selecione um objeto primeiro"); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        form.addView(section("IDENTIDADE")); val name = field(form, "Nome", node.name)
        form.addView(section("TRANSFORM")); val x = field(form, "Position X", node.transform.x.toString()); val y = field(form, "Position Y", node.transform.y.toString())
        val rotation = field(form, "Rotation", node.transform.rotation.toString()); val scaleX = field(form, "Scale X", node.transform.scaleX.toString()); val scaleY = field(form, "Scale Y", node.transform.scaleY.toString())
        form.addView(section("LAYOUT")); val width = field(form, "Width", node.width.toString()); val height = field(form, "Height", node.height.toString())
        form.addView(section("ORGANIZAÇÃO")); val tags = field(form, "Tags separadas por vírgula", node.tags.joinToString(","))
        if (node.assetPath != null) {
            form.addView(TextView(this).apply { text = "Asset  ${node.assetPath}"; setTextColor(MUTED); setPadding(0, dp(8), 0, dp(8)) })
            form.addView(Button(this).apply { text = "Trocar imagem"; isAllCaps = false; setOnClickListener { importSpriteFor(node) } })
        }
        AlertDialog.Builder(this).setTitle("Inspector  •  ${node.name}").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Aplicar") { _, _ ->
                val before = snapshot(node)
                val after = before.copy(name = name.text.toString().ifBlank { node.name }, x = x.floatOr(node.transform.x), y = y.floatOr(node.transform.y), rotation = rotation.floatOr(node.transform.rotation), scaleX = scaleX.floatOr(node.transform.scaleX), scaleY = scaleY.floatOr(node.transform.scaleY), width = width.floatOr(node.width), height = height.floatOr(node.height), tags = tags.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                undo.execute(snapshotCommand(node, before, after)); viewport.invalidate()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun section(label: String) = TextView(this).apply { text = label; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ACCENT_LIGHT); setPadding(0, dp(14), 0, dp(3)) }

    private fun showLuaEditor(node: SceneNode?) {
        if (node == null) { toast("Selecione um objeto primeiro"); return }
        val path = node.scriptPath ?: "Scripts/${safeFileName(node.name)}.lua"
        val source = runCatching { storage.readText(project, path) }.getOrElse { DEFAULT_LUA }
        LuaCodeEditorDialog(this, path, source) { code ->
            runCatching { storage.writeText(project, path, code); node.scriptPath = path; dirty = true }
                .onSuccess { consoleView.text = "[LUA] $path salvo e validado." }.onFailure { toast(it.message ?: "Erro ao salvar") }
        }.show()
    }

    private fun showBlocksEditor(node: SceneNode?) {
        if (node == null) { toast("Selecione um objeto primeiro"); return }
        val path = node.blocksPath ?: "Blocks/${safeFileName(node.name)}.blocks"
        val graph = runCatching { BlockGraph.fromJson(storage.readText(project, path)) }.getOrElse { BlockGraph() }
        val knownSignals = collectProjectSignals(graph)
        NovaBlocksEditorDialog(this, node.name, graph, knownSignals) { edited ->
            runCatching { storage.writeText(project, path, edited.toJson()); node.blocksPath = path; dirty = true }
                .onSuccess { consoleView.text = "[BLOCKS] $path salvo." }.onFailure { toast("Erro ao salvar blocos: ${it.message}") }
        }.show()
    }

    private fun collectProjectSignals(current: BlockGraph): MutableSet<String> {
        val result = linkedSetOf<String>()
        fun collectNode(node: BlockNode) { if (node.type == "send_signal" || node.type == "send_signal_value") node.fields["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add); node.children.forEach(::collectNode); node.elseChildren.forEach(::collectNode) }
        fun collectGraph(graph: BlockGraph) { graph.scripts.forEach { script -> if (script.event == "signal") script.argument?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add); script.body.forEach(::collectNode) } }
        collectGraph(current)
        scene.root.walk().mapNotNull { it.blocksPath }.distinct().forEach { blocksPath -> runCatching { BlockGraph.fromJson(storage.readText(project, blocksPath)) }.getOrNull()?.let(::collectGraph) }
        return result
    }

    private fun startPlayMode() { saveScene(silent = true); startActivity(Intent(this, PlayActivity::class.java).putExtra(PlayActivity.EXTRA_PROJECT_NAME, projectName)) }

    private fun saveScene(silent: Boolean = false) {
        runCatching { storage.saveMainScene(project, SceneCodec.encode(scene)); dirty = false }
            .onSuccess { if (!silent) { toast("Cena salva"); consoleView.text = "[ENGINE] Main.scene salva com sucesso." } }
            .onFailure { toast("Erro ao salvar: ${it.message}") }
    }

    private fun duplicateNode(node: SceneNode) { val copy = cloneNode(node).apply { name = uniqueNodeName(node.name); transform.x += 32f; transform.y += 32f }; scene.root.children += copy; viewport.selectNode(copy); dirty = true; viewport.invalidate() }
    private fun cloneNode(node: SceneNode): SceneNode = SceneNode(UUID.randomUUID().toString(), node.name, node.type, node.transform.copy(), node.width, node.height, node.text, node.assetPath, node.scriptPath, node.blocksPath, node.color, node.enabled, node.tags.toMutableSet(), node.properties.toMutableMap(), node.children.map(::cloneNode).toMutableList())
    private fun deleteNode(node: SceneNode) { if (removeNode(scene.root, node.id)) { viewport.selectNode(null); dirty = true; viewport.invalidate() } }
    private fun removeNode(parent: SceneNode, id: String): Boolean { val direct = parent.children.firstOrNull { it.id == id }; if (direct != null) return parent.children.remove(direct); return parent.children.any { removeNode(it, id) } }

    private fun resolveProjectFile(relativePath: String): DocumentFile? { val segments = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }; var current = project; for (segment in segments) current = current.findFile(segment) ?: return null; return current }
    private fun depthOf(root: SceneNode, id: String, depth: Int = 0): Int { if (root.id == id) return depth; root.children.forEach { val result = depthOf(it, id, depth + 1); if (result >= 0) return result }; return -1 }
    private fun uniqueNodeName(base: String): String { val names = scene.root.walk().map { it.name }.toSet(); if (base !in names) return base; var i = 2; while ("$base$i" in names) i++; return "$base$i" }

    private data class NodeSnapshot(val name: String, val x: Float, val y: Float, val rotation: Float, val scaleX: Float, val scaleY: Float, val width: Float, val height: Float, val tags: Set<String>)
    private fun snapshot(node: SceneNode) = NodeSnapshot(node.name, node.transform.x, node.transform.y, node.transform.rotation, node.transform.scaleX, node.transform.scaleY, node.width, node.height, node.tags.toSet())
    private fun applySnapshot(node: SceneNode, v: NodeSnapshot) { node.name = v.name; node.transform.x = v.x; node.transform.y = v.y; node.transform.rotation = v.rotation; node.transform.scaleX = v.scaleX; node.transform.scaleY = v.scaleY; node.width = v.width; node.height = v.height; node.tags.clear(); node.tags.addAll(v.tags); dirty = true; viewport.invalidate() }
    private fun snapshotCommand(node: SceneNode, before: NodeSnapshot, after: NodeSnapshot) = object : EditorCommand { override val label = "Editar ${node.name}"; override fun apply() = applySnapshot(node, after); override fun revert() = applySnapshot(node, before) }

    private fun field(parent: LinearLayout, label: String, value: String): EditText = EditText(this).apply { hint = label; setText(value); setTextColor(TEXT); setHintTextColor(MUTED); parent.addView(this) }
    private fun EditText.floatOr(default: Float): Float = text.toString().toFloatOrNull() ?: default
    private fun safeFileName(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toolButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(TEXT); background = rounded(Color.rgb(35, 40, 53), 10); minimumHeight = dp(42); minWidth = dp(62)
        setPadding(dp(12), 0, dp(12), 0); setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { rightMargin = dp(5) }
    }
    private fun smallButton(label: String, color: Int, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE); background = rounded(color, 11); setOnClickListener { action() }; minWidth = dp(48); minimumHeight = dp(40) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_NAME = "project_name"
        private val BG = Color.rgb(10, 12, 18); private val SURFACE = Color.rgb(16, 19, 27); private val SURFACE_2 = Color.rgb(21, 25, 35)
        private val TEXT = Color.rgb(238, 241, 247); private val MUTED = Color.rgb(145, 156, 177); private val ACCENT = Color.rgb(112, 74, 235); private val ACCENT_LIGHT = Color.rgb(177, 158, 255)
        private const val DEFAULT_LUA = """-- NovaForge Lua Script
function ready()
    NovaForge.print("${'$'}{self.name} ready")
end

function update(delta)
    -- self.x = self.x + 120 * delta
end

function onTouch(event)
    -- event.x, event.y, event.pressed
end
"""
    }
}
