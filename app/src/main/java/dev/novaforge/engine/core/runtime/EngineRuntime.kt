package dev.novaforge.engine.core.runtime

import dev.novaforge.engine.core.blocks.BlockGraph
import dev.novaforge.engine.core.blocks.NovaBlocksCompiler
import dev.novaforge.engine.core.model.SceneDocument
import dev.novaforge.engine.core.model.SceneNode
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import kotlin.math.abs

class EngineRuntime(
    val scene: SceneDocument,
    private val scriptLoader: (String) -> String?,
    private val blocksLoader: (String) -> String? = { null },
    val baseWidth: Int = 1920,
    val baseHeight: Int = 1080
) {
    val console = EngineConsole()
    val signalBus = SignalBus()
    var deltaSeconds: Float = 0f
        private set
    var requestStop: Boolean = false
    var requestReload: Boolean = false
    var touchX: Float = 0f
        private set
    var touchY: Float = 0f
        private set
    var touchPressed: Boolean = false
        private set
    var touchCount: Int = 0
        private set

    private data class LoadedScript(val owner: SceneNode, val instance: LuaScriptInstance)
    private val scripts = mutableListOf<LoadedScript>()
    private var accumulator = 0f

    fun start() {
        scripts.clear()
        scene.root.walk().filter { it.enabled }.forEach { node ->
            node.scriptPath?.let { path ->
                val source = scriptLoader(path)
                if (source == null) console.log(LogLevel.WARNING, "Script not found", path)
                else {
                    val instance = LuaScriptInstance(node, path, source, this).also { it.load() }
                    scripts += LoadedScript(node, instance)
                }
            }
            node.blocksPath?.let { path ->
                val source = blocksLoader(path)?.let { text ->
                    runCatching { NovaBlocksCompiler.compile(BlockGraph.fromJson(text)) }
                        .onFailure { console.log(LogLevel.ERROR, "NovaBlocks compile error: ${it.message}", path) }
                        .getOrNull()
                }
                if (source == null) console.log(LogLevel.WARNING, "NovaBlocks graph not found or invalid", path)
                else {
                    val instance = LuaScriptInstance(node, "$path#generated.lua", source, this).also { it.load() }
                    scripts += LoadedScript(node, instance)
                }
            }
        }
        scripts.forEach { it.instance.call("created") }
        scripts.forEach { it.instance.call("ready") }
        console.log(LogLevel.ENGINE, "Play Mode started with ${scene.root.walk().count()} objects")
    }

    fun frame(delta: Float) {
        deltaSeconds = delta.coerceIn(0f, 0.1f)
        scripts.forEach { it.instance.call("update", LuaValue.valueOf(deltaSeconds.toDouble())) }
        accumulator += deltaSeconds
        val fixed = 1f / 60f
        while (accumulator >= fixed) {
            scripts.forEach { it.instance.call("fixedUpdate", LuaValue.valueOf(fixed.toDouble())) }
            accumulator -= fixed
        }
    }

    fun touch(x: Float, y: Float, down: Boolean) {
        touchX = x
        touchY = y
        touchPressed = down
        touchCount = if (down) 1 else 0

        val event = LuaTable().apply {
            set("x", x.toDouble())
            set("y", y.toDouble())
            set("pressed", LuaValue.valueOf(down))
            set("count", touchCount)
        }
        scripts.forEach { it.instance.call("onTouch", event) }

        if (down) {
            val button = hitTestInteractive(x, y)
            if (button != null) {
                scripts.filter { it.owner.id == button.id }.forEach { loaded ->
                    // Lua scripts may expose onPressed(); NovaBlocks 0.1.x generated
                    // onPressed_<graphIndex>(), so call both forms for compatibility.
                    loaded.instance.call("onPressed")
                    repeat(64) { index -> loaded.instance.call("onPressed_$index") }
                }
                console.log(LogLevel.ENGINE, "Pressed ${button.name}")
            }
        }
    }

    private fun hitTestInteractive(x: Float, y: Float): SceneNode? =
        scene.root.walk()
            .filter {
                it.enabled && it.transform.visible &&
                    (it.type == "Button" || it.type == "TouchButton")
            }
            .sortedByDescending { it.transform.zIndex }
            .firstOrNull { node ->
                val t = node.transform
                val halfW = node.width * abs(t.scaleX) / 2f
                val halfH = node.height * abs(t.scaleY) / 2f
                x in (t.x - halfW)..(t.x + halfW) && y in (t.y - halfH)..(t.y + halfH)
            }

    fun findNode(value: String, byId: Boolean = false): SceneNode? =
        scene.root.walk().firstOrNull { if (byId) it.id == value else it.name == value }

    fun findByTag(tag: String): List<SceneNode> = scene.root.walk().filter { tag in it.tags }.toList()

    fun stop() {
        signalBus.clear()
        scripts.clear()
        touchPressed = false
        touchCount = 0
        console.log(LogLevel.ENGINE, "Play Mode stopped")
    }
}
