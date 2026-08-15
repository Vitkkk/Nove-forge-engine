package dev.novaforge.engine.core.runtime

import dev.novaforge.engine.core.model.SceneNode
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import kotlin.math.round

class LuaScriptInstance(
    private val owner: SceneNode,
    private val scriptPath: String,
    private val source: String,
    private val runtime: EngineRuntime
) {
    private val globals: Globals = JsePlatform.standardGlobals()
    private val proxies = linkedMapOf<String, LuaTable>()
    private val touchTable = LuaTable()
    private val screenTable = LuaTable()
    private var loaded = false

    init {
        globals.set("NovaForge", createApi())
        globals.set("self", proxyFor(owner))
        globals.set("touch", touchTable)
        globals.set("screen", screenTable)
        globals.set("clamp", object : ThreeArgFunction() {
            override fun call(value: LuaValue, min: LuaValue, max: LuaValue): LuaValue {
                val v = value.checkdouble(); val lo = min.checkdouble(); val hi = max.checkdouble()
                return LuaValue.valueOf(v.coerceIn(lo, hi))
            }
        })
        globals.set("round", object : OneArgFunction() {
            override fun call(value: LuaValue): LuaValue = LuaValue.valueOf(round(value.checkdouble()))
        })
        globals.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                runtime.console.log(LogLevel.LUA, (1..args.narg()).joinToString("\t") { args.arg(it).tojstring() }, scriptPath)
                return LuaValue.NONE
            }
        })
        syncEnvironment()
    }

    fun load() {
        try {
            globals.load(source, scriptPath).call()
            loaded = true
        } catch (e: LuaError) {
            report(e)
        }
    }

    fun call(name: String, vararg args: LuaValue) {
        if (!loaded || !owner.enabled) return
        val fn = globals.get(name)
        if (!fn.isfunction()) return
        syncEnvironment()
        syncAllToLua()
        try {
            when (args.size) {
                0 -> fn.call()
                1 -> fn.call(args[0])
                2 -> fn.call(args[0], args[1])
                else -> fn.invoke(LuaValue.varargsOf(Array(args.size) { args[it] }))
            }
            syncAllFromLua()
        } catch (e: LuaError) {
            report(e)
        }
    }

    private fun syncEnvironment() {
        touchTable.set("x", runtime.touchX.toDouble())
        touchTable.set("y", runtime.touchY.toDouble())
        touchTable.set("pressed", LuaValue.valueOf(runtime.touchPressed))
        touchTable.set("count", runtime.touchCount)
        screenTable.set("width", runtime.baseWidth)
        screenTable.set("height", runtime.baseHeight)
    }

    private fun createApi(): LuaTable = LuaTable().apply {
        set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                runtime.console.log(LogLevel.LUA, (1..args.narg()).joinToString(" ") { args.arg(it).tojstring() }, scriptPath)
                return LuaValue.NONE
            }
        })
        set("getDelta", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(runtime.deltaSeconds.toDouble())
        })
        set("getNode", object : OneArgFunction() {
            override fun call(name: LuaValue): LuaValue = runtime.findNode(name.checkjstring())?.let(::proxyFor) ?: LuaValue.NIL
        })
        set("findByTag", object : OneArgFunction() {
            override fun call(tag: LuaValue): LuaValue {
                val array = LuaTable()
                runtime.findByTag(tag.checkjstring()).forEachIndexed { i, node -> array.set(i + 1, proxyFor(node)) }
                return array
            }
        })
        set("emitSignal", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.arg(1).checkjstring()
                val value = if (args.narg() >= 2) luaToKotlin(args.arg(2)) else null
                syncAllFromLua()
                runtime.signalBus.emit(name, value)
                return LuaValue.NONE
            }
        })
        set("onSignal", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.arg(1).checkjstring()
                val callback = args.arg(2).checkfunction()
                runtime.signalBus.on(name) { value ->
                    syncEnvironment()
                    syncAllToLua()
                    try {
                        callback.call(kotlinToLua(value))
                        syncAllFromLua()
                    } catch (e: LuaError) {
                        report(e)
                    }
                }
                return LuaValue.NONE
            }
        })
        set("reloadScene", object : ZeroArgFunction() {
            override fun call(): LuaValue { runtime.requestReload = true; return LuaValue.NIL }
        })
        set("quit", object : ZeroArgFunction() {
            override fun call(): LuaValue { runtime.requestStop = true; return LuaValue.NIL }
        })
        set("isTouching", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(runtime.touchPressed)
        })
        set("touchX", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(runtime.touchX.toDouble())
        })
        set("touchY", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(runtime.touchY.toDouble())
        })
    }

    private fun proxyFor(node: SceneNode): LuaTable = proxies.getOrPut(node.id) {
        LuaTable().apply {
            set("id", node.id)
            set("name", node.name)
            set("move", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val target = runtime.findNode(node.id, byId = true) ?: return LuaValue.NONE
                    target.transform.x += args.arg(2).optdouble(0.0).toFloat()
                    target.transform.y += args.arg(3).optdouble(0.0).toFloat()
                    syncNodeToTable(target, this@apply)
                    return LuaValue.NONE
                }
            })
            set("setPosition", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val target = runtime.findNode(node.id, byId = true) ?: return LuaValue.NONE
                    target.transform.x = args.arg(2).optdouble(0.0).toFloat()
                    target.transform.y = args.arg(3).optdouble(0.0).toFloat()
                    syncNodeToTable(target, this@apply)
                    return LuaValue.NONE
                }
            })
            set("rotate", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val target = runtime.findNode(node.id, byId = true) ?: return LuaValue.NONE
                    target.transform.rotation += args.arg(2).optdouble(0.0).toFloat()
                    syncNodeToTable(target, this@apply)
                    return LuaValue.NONE
                }
            })
            set("setVisible", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val target = runtime.findNode(node.id, byId = true) ?: return LuaValue.NONE
                    target.transform.visible = args.arg(2).toboolean()
                    syncNodeToTable(target, this@apply)
                    return LuaValue.NONE
                }
            })
            set("destroy", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    runtime.findNode(node.id, byId = true)?.enabled = false
                    return LuaValue.NONE
                }
            })
            syncNodeToTable(node, this)
        }
    }

    private fun syncAllToLua() {
        proxies.forEach { (id, table) -> runtime.findNode(id, byId = true)?.let { syncNodeToTable(it, table) } }
    }

    private fun syncAllFromLua() {
        proxies.forEach { (id, table) -> runtime.findNode(id, byId = true)?.let { syncTableToNode(table, it) } }
    }

    private fun syncNodeToTable(node: SceneNode, table: LuaTable) {
        table.set("x", node.transform.x.toDouble())
        table.set("y", node.transform.y.toDouble())
        table.set("rotation", node.transform.rotation.toDouble())
        table.set("scaleX", node.transform.scaleX.toDouble())
        table.set("scaleY", node.transform.scaleY.toDouble())
        table.set("visible", LuaValue.valueOf(node.transform.visible))
        table.set("enabled", LuaValue.valueOf(node.enabled))
    }

    private fun syncTableToNode(table: LuaTable, node: SceneNode) {
        node.transform.x = table.get("x").optdouble(node.transform.x.toDouble()).toFloat()
        node.transform.y = table.get("y").optdouble(node.transform.y.toDouble()).toFloat()
        node.transform.rotation = table.get("rotation").optdouble(node.transform.rotation.toDouble()).toFloat()
        node.transform.scaleX = table.get("scaleX").optdouble(node.transform.scaleX.toDouble()).toFloat()
        node.transform.scaleY = table.get("scaleY").optdouble(node.transform.scaleY.toDouble()).toFloat()
        node.transform.visible = table.get("visible").optboolean(node.transform.visible)
        node.enabled = table.get("enabled").optboolean(node.enabled)
    }

    private fun report(error: LuaError) {
        val message = error.message ?: "Unknown Lua error"
        val line = Regex(Regex.escape(scriptPath) + ":(\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        runtime.console.log(LogLevel.ERROR, message, scriptPath, line)
    }

    private fun kotlinToLua(value: Any?): LuaValue = when (value) {
        null -> LuaValue.NIL
        is Boolean -> LuaValue.valueOf(value)
        is Number -> LuaValue.valueOf(value.toDouble())
        else -> LuaValue.valueOf(value.toString())
    }

    private fun luaToKotlin(value: LuaValue): Any? = when {
        value.isnil() -> null
        value.isboolean() -> value.toboolean()
        value.isnumber() -> value.todouble()
        else -> value.tojstring()
    }
}
