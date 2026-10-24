package com.nekobot.app.data.local.ai.preset

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 酒馆式预设（Preset）兼容层：Foreverse / SillyTavern Chat Completion 预设导入与运行时注入。
 *
 * 预设 JSON 格式（Foreverse preset-author 产线格式，兼容 ST 导出）：
 * {
 *   "name": "沉浸RP",
 *   "blocks": [
 *     {"identifier":"main","name":"破限","role":"system","content":"...","enabled":true},
 *     {"identifier":"chatHistory","marker":true},
 *     ...
 *   ]
 * }
 *
 * - 非 marker 块按顺序注入 PromptStack（priority 从 5 起步，每块 +1，保证在角色卡之前）
 * - marker 块（charDescription/chatHistory 等）由 Nekobot 原生管线负责，此处忽略
 * - 兼容 SillyTavern 导出的 prompts 数组 + prompt_order
 */
object PresetManager {

    private val gson = Gson()

    data class PresetBlock(
        val identifier: String,
        val name: String,
        val role: String,          // system / user / assistant（仅 system 参与 PromptStack）
        val content: String,
        val enabled: Boolean = true,
        val marker: Boolean = false,
        val injectionPosition: Int = -1,  // ST: injection_position（-1 相对/0/1 绝对）
        val depth: Int = 4
    )

    data class Preset(
        val name: String,
        val blocks: List<PresetBlock>
    )

    /** 已加载的全局预设（进程内缓存；持久化由调用方 SharedPreferences 完成） */
    @Volatile
    var activePreset: Preset? = null
        private set

    /** 解析 Foreverse / ST 预设 JSON。兼容 blocks[] 与 ST prompts[]+prompt_order 两种格式。 */
    fun parse(json: String): Result<Preset> = try {
        val root = JsonParser.parseString(json).asJsonObject
        val name = root.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("presetName")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "导入预设"
        val blocks = mutableListOf<PresetBlock>()
        if (root.has("blocks") && root.get("blocks").isJsonArray) {
            for (el in root.getAsJsonArray("blocks")) {
                val b = el.asJsonObject
                blocks.add(
                    PresetBlock(
                        identifier = b.get("identifier")?.asString ?: "block_${blocks.size}",
                        name = b.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                        role = b.get("role")?.takeIf { it.isJsonPrimitive }?.asString ?: "system",
                        content = b.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                        enabled = b.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                        marker = b.get("marker")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    )
                )
            }
        } else if (root.has("prompts") && root.get("prompts").isJsonArray) {
            // SillyTavern 导出格式：prompts[] + prompt_order（按 character_id=100001 的顺序）
            val promptsById = mutableMapOf<String, JsonObject>()
            for (el in root.getAsJsonArray("prompts")) {
                val p = el.asJsonObject
                val id = p.get("identifier")?.asString ?: continue
                promptsById[id] = p
            }
            val orderIds = mutableListOf<String>()
            if (root.has("prompt_order") && root.get("prompt_order").isJsonArray) {
                for (po in root.getAsJsonArray("prompt_order")) {
                    val poObj = po.asJsonObject
                    if ((poObj.get("character_id")?.asInt ?: 100001) == 100001) {
                        for (o in poObj.getAsJsonArray("order")) {
                            val id = o.asJsonObject.get("identifier")?.asString ?: continue
                            if (o.asJsonObject.get("enabled")?.asBoolean != false) orderIds.add(id)
                        }
                    }
                }
            }
            val ordered = if (orderIds.isEmpty()) promptsById.keys else orderIds
            for (id in ordered) {
                val p = promptsById[id] ?: continue
                blocks.add(
                    PresetBlock(
                        identifier = id,
                        name = p.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: id,
                        role = p.get("role")?.takeIf { it.isJsonPrimitive }?.asString ?: "system",
                        content = p.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                        enabled = true,
                        marker = p.get("marker")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                        injectionPosition = p.get("injection_position")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1,
                        depth = p.get("depth")?.takeIf { it.isJsonPrimitive }?.asInt ?: 4
                    )
                )
            }
        }
        if (blocks.isEmpty()) return Result.failure(IllegalArgumentException("预设里没有任何 block（blocks[] 或 prompts[] 都为空）"))
        Result.success(Preset(name, blocks))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 设为全局生效预设。 */
    fun activate(preset: Preset) {
        activePreset = preset
    }

    /** 序列化当前预设为 JSON（持久化用）。 */
    fun serialize(preset: Preset): String {
        val root = JsonObject()
        root.addProperty("name", preset.name)
        val arr = com.google.gson.JsonArray()
        for (b in preset.blocks) {
            val o = JsonObject()
            o.addProperty("identifier", b.identifier)
            o.addProperty("name", b.name)
            o.addProperty("role", b.role)
            o.addProperty("content", b.content)
            o.addProperty("enabled", b.enabled)
            o.addProperty("marker", b.marker)
            arr.add(o)
        }
        root.add("blocks", arr)
        return gson.toJson(root)
    }

    fun deactivate() {
        activePreset = null
    }

    /** 统计信息（供导入工具输出）。 */
    fun stats(preset: Preset): String {
        val content = preset.blocks.filter { it.enabled && !it.marker }
        return "name=${preset.name} blocks=${preset.blocks.size} active=${content.size} markers=${preset.blocks.count { it.marker }}"
    }
}