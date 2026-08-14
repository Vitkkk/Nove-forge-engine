package dev.novaforge.engine.core.blocks

import org.json.JSONArray
import org.json.JSONObject

/** NovaBlocks IR: touch UI -> versioned graph -> deterministic Lua. */
data class BlockGraph(
    val formatVersion: Int = 2,
    val scripts: MutableList<BlockScript> = mutableListOf(),
    val variables: MutableMap<String, String> = linkedMapOf()
) {
    fun toJson(): String = JSONObject()
        .put("formatVersion", formatVersion)
        .put("variables", JSONObject(variables as Map<*, *>))
        .put("scripts", JSONArray().also { a -> scripts.forEach { a.put(it.toJson()) } })
        .toString(2)

    companion object {
        fun fromJson(text: String): BlockGraph {
            val json = JSONObject(text)
            val graph = BlockGraph(json.optInt("formatVersion", 1))
            json.optJSONObject("variables")?.let { vars -> vars.keys().forEach { graph.variables[it] = vars.optString(it, "0") } }
            val scripts = json.optJSONArray("scripts") ?: JSONArray()
            for (i in 0 until scripts.length()) graph.scripts += BlockScript.fromJson(scripts.getJSONObject(i))
            return graph
        }
    }
}

data class BlockScript(
    val event: String,
    val argument: String? = null,
    val body: MutableList<BlockNode> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("event", event)
        .put("argument", argument)
        .put("body", JSONArray().also { a -> body.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): BlockScript {
            val body = mutableListOf<BlockNode>()
            val array = json.optJSONArray("body") ?: JSONArray()
            for (i in 0 until array.length()) body += BlockNode.fromJson(array.getJSONObject(i))
            return BlockScript(
                json.getString("event"),
                json.optString("argument").takeIf { it.isNotBlank() && it != "null" },
                body
            )
        }
    }
}

data class BlockNode(
    val type: String,
    val fields: MutableMap<String, String> = linkedMapOf(),
    val children: MutableList<BlockNode> = mutableListOf(),
    val elseChildren: MutableList<BlockNode> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("fields", JSONObject(fields as Map<*, *>))
        .put("children", JSONArray().also { a -> children.forEach { a.put(it.toJson()) } })
        .put("else", JSONArray().also { a -> elseChildren.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): BlockNode {
            val fields = linkedMapOf<String, String>()
            json.optJSONObject("fields")?.let { f -> f.keys().forEach { fields[it] = f.optString(it) } }
            fun decodeArray(name: String): MutableList<BlockNode> = mutableListOf<BlockNode>().also { out ->
                val a = json.optJSONArray(name) ?: JSONArray()
                for (i in 0 until a.length()) out += fromJson(a.getJSONObject(i))
            }
            return BlockNode(json.getString("type"), fields, decodeArray("children"), decodeArray("else"))
        }
    }
}

data class BlockDefinition(
    val id: String,
    val category: String,
    val label: String,
    val defaults: Map<String, String> = emptyMap(),
    val fieldKey: String? = null,
    val color: Long = 0xFF4A90C2
)

object BlockRegistry {
    val categories = listOf(
        "Events", "Control", "Movement", "Looks", "Variables", "Signals", "Input", "Touch",
        "Objects", "Scenes", "Camera", "UI", "Audio", "Time", "Strings", "Lists", "Physics",
        "Debug", "Functions"
    )

    private val event = 0xFFD85A16
    private val control = 0xFFFF9862
    private val movement = 0xFF4894C8
    private val looks = 0xFF78A94C
    private val variables = 0xFFF45151
    private val signals = 0xFF9C4CC2
    private val input = 0xFFA98008
    private val objectColor = 0xFF3F67B5
    private val utility = 0xFF1597A8

    val definitions = listOf(
        BlockDefinition("event_ready", "Events", "Quando a cena começar", color = event),
        BlockDefinition("event_created", "Events", "Quando este objeto for criado", color = event),
        BlockDefinition("event_update", "Events", "A cada frame", color = event),
        BlockDefinition("event_fixed", "Events", "A cada physics frame", color = event),
        BlockDefinition("event_touch", "Events", "Quando tocar neste objeto", color = event),
        BlockDefinition("event_signal", "Events", "Quando receber sinal [nome]", mapOf("name" to "signal"), "name", event),
        BlockDefinition("event_pressed", "Events", "Quando botão for pressionado", color = event),

        BlockDefinition("wait", "Control", "Aguardar () segundos", mapOf("seconds" to "1"), "seconds", control),
        BlockDefinition("if", "Control", "Se <condição> então", mapOf("condition" to "true"), "condition", control),
        BlockDefinition("if_else", "Control", "Se <condição> então / Senão", mapOf("condition" to "true"), "condition", control),
        BlockDefinition("repeat", "Control", "Repetir () vezes", mapOf("count" to "10"), "count", control),
        BlockDefinition("while", "Control", "Repetir até <condição>", mapOf("condition" to "false"), "condition", control),
        BlockDefinition("stop_script", "Control", "Parar script", color = control),

        BlockDefinition("move_x", "Movement", "Alterar X por ()", mapOf("value" to "10"), "value", movement),
        BlockDefinition("move_y", "Movement", "Alterar Y por ()", mapOf("value" to "10"), "value", movement),
        BlockDefinition("set_x", "Movement", "Definir X para ()", mapOf("value" to "0"), "value", movement),
        BlockDefinition("set_y", "Movement", "Definir Y para ()", mapOf("value" to "0"), "value", movement),
        BlockDefinition("set_position", "Movement", "Colocar em X () Y ()", mapOf("x" to "0", "y" to "0"), "x", movement),
        BlockDefinition("move_direction", "Movement", "Mover na direção () por ()", mapOf("angle" to "90", "distance" to "5"), "distance", movement),
        BlockDefinition("rotate", "Movement", "Rotacionar por ()", mapOf("value" to "5"), "value", movement),
        BlockDefinition("set_rotation", "Movement", "Definir rotação para ()", mapOf("value" to "0"), "value", movement),
        BlockDefinition("glide", "Movement", "Deslizar () segundos para X () Y ()", mapOf("seconds" to "1", "x" to "0", "y" to "0"), "seconds", movement),

        BlockDefinition("set_visible", "Looks", "Definir visível para <true>", mapOf("value" to "true"), "value", looks),
        BlockDefinition("set_scale_x", "Looks", "Definir escala X para ()", mapOf("value" to "1"), "value", looks),
        BlockDefinition("set_scale_y", "Looks", "Definir escala Y para ()", mapOf("value" to "1"), "value", looks),
        BlockDefinition("set_text", "Looks", "Definir texto para ()", mapOf("value" to "\"texto\""), "value", looks),

        BlockDefinition("set_variable", "Variables", "Definir variável [nome] para ()", mapOf("name" to "speed", "value" to "0"), "value", variables),
        BlockDefinition("change_variable", "Variables", "Alterar variável [nome] por ()", mapOf("name" to "speed", "value" to "1"), "value", variables),
        BlockDefinition("list_add", "Variables", "Adicionar () à lista [nome]", mapOf("name" to "items", "value" to "0"), "value", variables),

        BlockDefinition("send_signal", "Signals", "Enviar sinal [nome]", mapOf("name" to "signal", "value" to "nil"), "value", signals),
        BlockDefinition("send_signal_value", "Signals", "Enviar sinal [nome] com valor ()", mapOf("name" to "signal", "value" to "0"), "value", signals),

        BlockDefinition("touching", "Touch", "Tela está sendo tocada?", color = input),
        BlockDefinition("touch_x", "Touch", "Posição X do toque", color = input),
        BlockDefinition("touch_y", "Touch", "Posição Y do toque", color = input),
        BlockDefinition("touch_count", "Touch", "Número de toques", color = input),

        BlockDefinition("create_object", "Objects", "Criar objeto [nome] em X () Y ()", mapOf("name" to "Object", "x" to "0", "y" to "0"), "x", objectColor),
        BlockDefinition("destroy_self", "Objects", "Destruir este objeto", color = objectColor),
        BlockDefinition("set_enabled", "Objects", "Ativar/Desativar este objeto", mapOf("value" to "true"), "value", objectColor),
        BlockDefinition("load_scene", "Scenes", "Carregar cena [caminho]", mapOf("name" to "Scenes/Main.scene"), "name", objectColor),
        BlockDefinition("reload_scene", "Scenes", "Recarregar cena", color = objectColor),

        BlockDefinition("camera_zoom", "Camera", "Definir zoom da câmera para ()", mapOf("value" to "1"), "value", utility),
        BlockDefinition("play_audio", "Audio", "Tocar áudio", color = signals),
        BlockDefinition("stop_audio", "Audio", "Parar áudio", color = signals),
        BlockDefinition("print", "Debug", "Imprimir ()", mapOf("value" to "\"Olá\""), "value", utility)
    )

    fun inCategory(category: String) = definitions.filter { it.category == category }
    fun byId(id: String) = definitions.firstOrNull { it.id == id }
}

object NovaBlocksCompiler {
    fun compile(graph: BlockGraph): String = buildString {
        appendLine("-- Generated by NovaBlocks. Do not edit this generated buffer directly.")
        appendLine("local __nf_vars = {}")
        graph.variables.forEach { (name, value) -> appendLine("__nf_vars[${luaString(name)}] = ${expr(value)}") }
        graph.scripts.forEachIndexed { index, script ->
            when (script.event) {
                "ready", "created" -> function(if (script.event == "ready") "ready" else "created", script.body)
                "update" -> function("update", script.body, "delta")
                "fixed_update" -> function("fixedUpdate", script.body, "delta")
                "touch" -> function("onTouch", script.body, "event")
                "signal" -> {
                    val signal = luaString(script.argument ?: "signal")
                    appendLine("NovaForge.onSignal($signal, function(signalValue)")
                    emitNodes(script.body, "    ")
                    appendLine("end)")
                }
                "pressed" -> function("onPressed_${index}", script.body)
            }
        }
    }

    private fun StringBuilder.function(name: String, body: List<BlockNode>, args: String = "") {
        appendLine("function $name($args)")
        emitNodes(body, "    ")
        appendLine("end")
    }

    private fun StringBuilder.emitNodes(nodes: List<BlockNode>, indent: String) {
        nodes.forEach { node ->
            when (node.type) {
                "move_x" -> appendLine("${indent}self.x = self.x + ${expr(node.fields["value"] ?: "0")}")
                "move_y" -> appendLine("${indent}self.y = self.y + ${expr(node.fields["value"] ?: "0")}")
                "set_x" -> appendLine("${indent}self.x = ${expr(node.fields["value"] ?: "0")}")
                "set_y" -> appendLine("${indent}self.y = ${expr(node.fields["value"] ?: "0")}")
                "set_position" -> appendLine("${indent}self:setPosition(${expr(node.fields["x"] ?: "0")}, ${expr(node.fields["y"] ?: "0")})")
                "move_direction" -> {
                    val a = expr(node.fields["angle"] ?: "0")
                    val d = expr(node.fields["distance"] ?: "0")
                    appendLine("${indent}self:move(math.cos(math.rad($a)) * ($d), math.sin(math.rad($a)) * ($d))")
                }
                "rotate" -> appendLine("${indent}self.rotation = self.rotation + ${expr(node.fields["value"] ?: "0")}")
                "set_rotation" -> appendLine("${indent}self.rotation = ${expr(node.fields["value"] ?: "0")}")
                "set_scale_x" -> appendLine("${indent}self.scaleX = ${expr(node.fields["value"] ?: "1")}")
                "set_scale_y" -> appendLine("${indent}self.scaleY = ${expr(node.fields["value"] ?: "1")}")
                "set_visible" -> appendLine("${indent}self.visible = ${expr(node.fields["value"] ?: "true")}")
                "send_signal", "send_signal_value" -> appendLine("${indent}NovaForge.emitSignal(${luaString(node.fields["name"] ?: "signal")}, ${expr(node.fields["value"] ?: "nil")})")
                "set_variable" -> appendLine("${indent}__nf_vars[${luaString(node.fields["name"] ?: "value")}] = ${expr(node.fields["value"] ?: "0")}")
                "change_variable" -> {
                    val key = luaString(node.fields["name"] ?: "value")
                    appendLine("${indent}__nf_vars[$key] = (__nf_vars[$key] or 0) + ${expr(node.fields["value"] ?: "0")}")
                }
                "if", "if_else" -> {
                    appendLine("${indent}if ${expr(node.fields["condition"] ?: "false")} then")
                    emitNodes(node.children, "$indent    ")
                    if (node.elseChildren.isNotEmpty()) {
                        appendLine("${indent}else")
                        emitNodes(node.elseChildren, "$indent    ")
                    }
                    appendLine("${indent}end")
                }
                "repeat" -> {
                    appendLine("${indent}for __nf_i = 1, math.max(0, math.floor(${expr(node.fields["count"] ?: "0")})) do")
                    emitNodes(node.children, "$indent    ")
                    appendLine("${indent}end")
                }
                "while" -> {
                    appendLine("${indent}while not (${expr(node.fields["condition"] ?: "false")}) do")
                    emitNodes(node.children, "$indent    ")
                    appendLine("${indent}end")
                }
                "print" -> appendLine("${indent}NovaForge.print(${expr(node.fields["value"] ?: "\"\"")})")
                "destroy_self" -> appendLine("${indent}self:destroy()")
                "reload_scene" -> appendLine("${indent}NovaForge.reloadScene()")
                "stop_script" -> appendLine("${indent}return")
                "wait" -> appendLine("${indent}NovaForge.wait(${expr(node.fields["seconds"] ?: "0")})")
                else -> appendLine("${indent}-- '${node.type}' is stored in NovaBlocks IR; runtime binding is pending")
            }
        }
    }

    private fun expr(raw: String): String {
        var value = raw.trim().ifEmpty { "0" }
        require(value.length <= 320) { "Expression too long" }
        require(value.matches(Regex("[A-Za-z0-9_ .,+\\-*/%^()<>!=\\[\\]'\"#:]*"))) { "Unsafe NovaBlocks expression" }
        value = value.replace("delta time", "delta", ignoreCase = true)
        Regex("\\bvar:([A-Za-z_][A-Za-z0-9_]*)").findAll(value).forEach { match ->
            value = value.replace(match.value, "__nf_vars[${luaString(match.groupValues[1])}]")
        }
        return value
    }

    private fun luaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
