package dev.novaforge.engine.core.model

import org.json.JSONObject

data class ProjectConfig(
    val formatVersion: Int = 1,
    val name: String,
    val engineVersion: String = "0.2.0",
    val mainScene: String = "Scenes/Main.scene",
    val width: Int = 1920,
    val height: Int = 1080,
    val orientation: String = "landscape",
    val stretchMode: String = "fit",
    val aspectMode: String = "keep",
    val packageName: String = "dev.novaforge.game",
    val versionName: String = "0.1.0"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("formatVersion", formatVersion)
        .put("name", name)
        .put("engineVersion", engineVersion)
        .put("mainScene", mainScene)
        .put("resolution", JSONObject().put("width", width).put("height", height))
        .put("orientation", orientation)
        .put("stretchMode", stretchMode)
        .put("aspectMode", aspectMode)
        .put("packageName", packageName)
        .put("version", versionName)

    companion object {
        fun fromJson(json: JSONObject): ProjectConfig {
            val resolution = json.optJSONObject("resolution") ?: JSONObject()
            return ProjectConfig(
                formatVersion = json.optInt("formatVersion", 1),
                name = json.getString("name"),
                engineVersion = json.optString("engineVersion", "0.1.0"),
                mainScene = json.optString("mainScene", "Scenes/Main.scene"),
                width = resolution.optInt("width", 1920),
                height = resolution.optInt("height", 1080),
                orientation = json.optString("orientation", "landscape"),
                stretchMode = json.optString("stretchMode", "fit"),
                aspectMode = json.optString("aspectMode", "keep"),
                packageName = json.optString("packageName", "dev.novaforge.game"),
                versionName = json.optString("version", "0.1.0")
            )
        }
    }
}

data class Transform2D(
    var x: Float = 0f,
    var y: Float = 0f,
    var rotation: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var visible: Boolean = true,
    var zIndex: Int = 0
)

data class SceneNode(
    val id: String,
    var name: String,
    var type: String = "Node2D",
    var transform: Transform2D = Transform2D(),
    var width: Float = 120f,
    var height: Float = 120f,
    var text: String? = null,
    var assetPath: String? = null,
    var scriptPath: String? = null,
    var blocksPath: String? = null,
    var color: Int = 0xFF8B5CF6.toInt(),
    var enabled: Boolean = true,
    val tags: MutableSet<String> = linkedSetOf(),
    val properties: MutableMap<String, String> = linkedMapOf(),
    val children: MutableList<SceneNode> = mutableListOf()
) {
    fun walk(): Sequence<SceneNode> = sequence {
        yield(this@SceneNode)
        children.forEach { yieldAll(it.walk()) }
    }
}

data class SceneDocument(
    val formatVersion: Int = 1,
    val id: String,
    var name: String,
    val root: SceneNode
)

data class ProjectSummary(
    val name: String,
    val modifiedAt: Long,
    val engineVersion: String,
    val documentUri: String
)
