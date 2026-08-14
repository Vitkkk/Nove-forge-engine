package dev.novaforge.engine.core.runtime

import dev.novaforge.engine.core.blocks.BlockGraph
import dev.novaforge.engine.core.blocks.NovaBlocksCompiler
import dev.novaforge.engine.core.model.SceneDocument
import dev.novaforge.engine.core.model.SceneNode
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

class EngineRuntime(
    val scene: SceneDocument,
    private val scriptLoader: (String) -> String?,
    private val blocksLoader: (String) -> String? = { null }
) {
    val console = EngineConsole()
    val signalBus = SignalBus()
    var deltaSeconds: Float = 0f
        private set
    var requestStop: Boolean = false
    var requestReload: Boolean = false
    private val scripts = mutableListOf<LuaScriptInstance>()
    private var accumulator = 0f

    fun start() {
        scripts.clear()
        scene.root.walk().filter { it.enabled }.forEach { node ->
            node.scriptPath?.let { path ->
                val source = scriptLoader(path)
                if (source == null) console.log(LogLevel.WARNING, "Script not found", path)
                else scripts += LuaScriptInstance(node, path, source, this).also { it.load() }
            }
            node.blocksPath?.let { path ->
                val source = blocksLoader(path)?.let { text ->
                    runCatching { NovaBlocksCompiler.compile(BlockGraph.fromJson(text)) }
                        .onFailure { console.log(LogLevel.ERROR, "NovaBlocks compile error: ${it.message}", path) }
                        .getOrNull()
                }
                if (source == null) console.log(LogLevel.WARNING, "NovaBlocks graph not found or invalid", path)
                else scripts += LuaScriptInstance(node, "$path#generated.lua", source, this).also { it.load() }
            }
        }
        scripts.forEach { it.call("ready") }
        console.log(LogLevel.ENGINE, "Play Mode started with ${scene.root.walk().count()} objects")
    }

    fun frame(delta: Float) {
        deltaSeconds = delta.coerceIn(0f, 0.1f)
        scripts.forEach { it.call("update", LuaValue.valueOf(deltaSeconds.toDouble())) }
        accumulator += deltaSeconds
        val fixed = 1f / 60f
        while (accumulator >= fixed) {
            scripts.forEach { it.call("fixedUpdate", LuaValue.valueOf(fixed.toDouble())) }
            accumulator -= fixed
        }
    }

    fun touch(x: Float, y: Float, down: Boolean) {
        val event = LuaTable().apply {
            set("x", x.toDouble())
            set("y", y.toDouble())
            set("pressed", LuaValue.valueOf(down))
        }
        scripts.forEach { it.call("onTouch", event) }
    }

    fun findNode(value: String, byId: Boolean = false): SceneNode? =
        scene.root.walk().firstOrNull { if (byId) it.id == value else it.name == value }

    fun findByTag(tag: String): List<SceneNode> = scene.root.walk().filter { tag in it.tags }.toList()

    fun stop() {
        signalBus.clear()
        scripts.clear()
        console.log(LogLevel.ENGINE, "Play Mode stopped")
    }
}
