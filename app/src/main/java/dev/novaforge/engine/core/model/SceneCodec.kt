package dev.novaforge.engine.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object SceneCodec {
    fun defaultScene(): SceneDocument {
        val root = SceneNode(UUID.randomUUID().toString(), "Main", "Node")
        root.children += SceneNode(UUID.randomUUID().toString(), "Camera", "Camera2D")
        root.children += SceneNode(
            UUID.randomUUID().toString(),
            "Player",
            "Node2D",
            Transform2D(480f, 270f),
            width = 160f,
            height = 160f,
            text = "Player",
            scriptPath = "Scripts/Player.lua"
        )
        return SceneDocument(1, UUID.randomUUID().toString(), "Main", root)
    }

    fun encode(scene: SceneDocument): String = JSONObject()
        .put("formatVersion", scene.formatVersion)
        .put("id", scene.id)
        .put("name", scene.name)
        .put("root", encodeNode(scene.root))
        .toString(2)

    fun decode(text: String): SceneDocument {
        val json = JSONObject(text)
        return SceneDocument(
            formatVersion = json.optInt("formatVersion", 1),
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Scene"),
            root = decodeNode(json.getJSONObject("root"))
        )
    }

    fun deepCopy(scene: SceneDocument): SceneDocument = decode(encode(scene))

    private fun encodeNode(node: SceneNode): JSONObject {
        val t = node.transform
        return JSONObject()
            .put("id", node.id)
            .put("name", node.name)
            .put("type", node.type)
            .put("transform", JSONObject()
                .put("x", t.x.toDouble()).put("y", t.y.toDouble())
                .put("rotation", t.rotation.toDouble())
                .put("scaleX", t.scaleX.toDouble()).put("scaleY", t.scaleY.toDouble())
                .put("visible", t.visible).put("zIndex", t.zIndex))
            .put("size", JSONObject().put("width", node.width.toDouble()).put("height", node.height.toDouble()))
            .put("text", node.text)
            .put("assetPath", node.assetPath)
            .put("scriptPath", node.scriptPath)
            .put("blocksPath", node.blocksPath)
            .put("color", node.color)
            .put("enabled", node.enabled)
            .put("tags", JSONArray(node.tags.toList()))
            .put("properties", JSONObject(node.properties as Map<*, *>))
            .put("children", JSONArray().also { array -> node.children.forEach { array.put(encodeNode(it)) } })
    }

    private fun decodeNode(json: JSONObject): SceneNode {
        val t = json.optJSONObject("transform") ?: JSONObject()
        val size = json.optJSONObject("size") ?: JSONObject()
        val node = SceneNode(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Node"),
            type = json.optString("type", "Node2D"),
            transform = Transform2D(
                x = t.optDouble("x", 0.0).toFloat(),
                y = t.optDouble("y", 0.0).toFloat(),
                rotation = t.optDouble("rotation", 0.0).toFloat(),
                scaleX = t.optDouble("scaleX", 1.0).toFloat(),
                scaleY = t.optDouble("scaleY", 1.0).toFloat(),
                visible = t.optBoolean("visible", true),
                zIndex = t.optInt("zIndex", 0)
            ),
            width = size.optDouble("width", 120.0).toFloat(),
            height = size.optDouble("height", 120.0).toFloat(),
            text = json.optString("text").takeIf { it.isNotEmpty() && it != "null" },
            assetPath = json.optString("assetPath").takeIf { it.isNotEmpty() && it != "null" },
            scriptPath = json.optString("scriptPath").takeIf { it.isNotEmpty() && it != "null" },
            blocksPath = json.optString("blocksPath").takeIf { it.isNotEmpty() && it != "null" },
            color = json.optInt("color", 0xFF8B5CF6.toInt()),
            enabled = json.optBoolean("enabled", true)
        )
        json.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) node.tags += arr.optString(i)
        }
        json.optJSONObject("properties")?.let { props ->
            props.keys().forEach { key -> node.properties[key] = props.optString(key) }
        }
        json.optJSONArray("children")?.let { arr ->
            for (i in 0 until arr.length()) node.children += decodeNode(arr.getJSONObject(i))
        }
        return node
    }
}
