package com.nekobot.app.data.local.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * SillyTavern 世界书统一导入器（参照 Foreverse 智能导入逻辑移植）。
 *
 * 支持的输入：
 * - 独立世界书 JSON：entries 为数组（ST 新版）或对象（ST 旧版 map）；兼容顶层 entries / character_book / embedded_lorebook
 * - 角色卡内嵌世界书：ST v2 character_book / v3 data.character_book，或 Foreverse embedded_lorebook
 * - PNG：提取 ccv3/chara tEXt/iTXt chunk（base64 JSON），再走卡内嵌逻辑
 */
object WorldBookImporter {

    data class ImportedBook(
        val name: String,
        val description: String?,
        val entries: List<ImportedEntry>
    )

    data class ImportedEntry(
        val keys: List<String>,
        val content: String,
        val comment: String?,
        val enabled: Boolean,
        val constant: Boolean,
        val selective: Boolean,
        val insertionOrder: Int,
        val priority: Int,
        val position: String,
        val caseSensitive: Boolean,
        val matchMode: String,
        val displayIndex: Int
    )

    class ImportException(message: String) : Exception(message)

    /** 从任意 JSON 文本解析世界书；失败抛 ImportException。 */
    fun fromJson(json: String): ImportedBook {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw ImportException("JSON 解析失败：${'$'}{e.message}")
        }
        if (!root.isJsonObject) throw ImportException("不是 JSON 对象")
        return fromJsonObject(root.asJsonObject)
    }

    private fun fromJsonObject(root: JsonObject): ImportedBook {
        // 兼容 v3 卡顶层 { spec, data: {...} }
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val bookObj: JsonObject? = when {
            root.has("entries") -> root
            root.get("character_book")?.isJsonObject == true -> root.getAsJsonObject("character_book")
            root.get("embedded_lorebook")?.isJsonObject == true -> root.getAsJsonObject("embedded_lorebook")
            data?.get("character_book")?.isJsonObject == true -> data.getAsJsonObject("character_book")
            data?.get("embedded_lorebook")?.isJsonObject == true -> data.getAsJsonObject("embedded_lorebook")
            data?.has("entries") == true -> data
            root.get("world")?.isJsonObject == true -> root.getAsJsonObject("world")
            else -> null
        } ?: throw ImportException("未找到世界书数据（缺少 entries / character_book / embedded_lorebook）")

        val name = str(bookObj, "name")
            ?: str(root, "name")
            ?: "导入的世界书"
        val description = str(bookObj, "description") ?: str(root, "description")

        val entriesEl: JsonElement? = bookObj.get("entries")
        val parsed = mutableListOf<ImportedEntry>()
        when {
            entriesEl != null && entriesEl.isJsonArray -> {
                entriesEl.asJsonArray.forEachIndexed { idx, e ->
                    if (e.isJsonObject) parsed.add(parseEntry(e.asJsonObject, idx))
                }
            }
            entriesEl != null && entriesEl.isJsonObject -> {
                // ST 旧版：entries 为 { "0": {...}, "1": {...} }；按数字键排序
                val sorted = entriesEl.asJsonObject.entrySet()
                    .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
                    .sortedBy { it.first }
                sorted.forEachIndexed { idx, (_, v) ->
                    if (v.isJsonObject) parsed.add(parseEntry(v.asJsonObject, idx))
                }
            }
            else -> throw ImportException("世界书没有条目（entries 为空或格式不支持）")
        }
        if (parsed.isEmpty()) throw ImportException("没有解析到任何有效条目")
        return ImportedBook(name = name, description = description, entries = parsed)
    }

    /** 从角色卡 JSON（v2/v3 均可）中提取内嵌世界书；无则返回 null。 */
    fun fromCharacterCard(json: String): ImportedBook? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            val bookEl: JsonObject = when {
                data?.get("character_book")?.isJsonObject == true -> data.getAsJsonObject("character_book")
                root.get("character_book")?.isJsonObject == true -> root.getAsJsonObject("character_book")
                data?.get("embedded_lorebook")?.isJsonObject == true -> data.getAsJsonObject("embedded_lorebook")
                root.get("embedded_lorebook")?.isJsonObject == true -> root.getAsJsonObject("embedded_lorebook")
                else -> return null
            } ?: return null
            val cardName = str(data ?: root, "name")
            val bookName = str(bookEl, "name") ?: (cardName?.let { "${'$'}{it}的世界书" } ?: "内嵌世界书")
            val bookDesc = str(bookEl, "description")
            // 直接在 bookEl 上补 name/description 后解析
            val entriesEl = bookEl.get("entries") ?: return null
            val parsed = mutableListOf<ImportedEntry>()
            when {
                entriesEl.isJsonArray -> entriesEl.asJsonArray.forEachIndexed { idx, e ->
                    if (e.isJsonObject) parsed.add(parseEntry(e.asJsonObject, idx))
                }
                entriesEl.isJsonObject -> entriesEl.asJsonObject.entrySet()
                    .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
                    .sortedBy { it.first }
                    .forEachIndexed { idx, (_, v) ->
                        if (v.isJsonObject) parsed.add(parseEntry(v.asJsonObject, idx))
                    }
                else -> return null
            }
            if (parsed.isEmpty()) return null
            ImportedBook(name = bookName, description = bookDesc, entries = parsed)
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 PNG 字节中提取内嵌角色卡 JSON（ccv3 优先，chara 其次）。
     */
    fun extractCardJsonFromPng(bytes: ByteArray): String? {
        val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < pngSig.size || !bytes.copyOfRange(0, pngSig.size).contentEquals(pngSig)) return null
        var offset = pngSig.size
        var foundV3: String? = null
        var foundV2: String? = null
        while (offset + 8 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            if (offset + 4 > bytes.size) break
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            offset += 4
            if (length < 0 || offset + length + 4 > bytes.size) break
            val data = bytes.copyOfRange(offset, offset + length)
            offset += length + 4
            if (type == "tEXt") {
                val nullIdx = data.indexOf(0.toByte())
                if (nullIdx > 0) {
                    val keyword = String(data, 0, nullIdx, Charsets.US_ASCII)
                    val text = String(data, nullIdx + 1, data.size - nullIdx - 1, Charsets.US_ASCII)
                    when (keyword) {
                        "ccv3" -> foundV3 = text
                        "chara" -> foundV2 = text
                    }
                }
            } else if (type == "iTXt") {
                val nullIdx = data.indexOf(0.toByte())
                if (nullIdx > 0) {
                    val keyword = String(data, 0, nullIdx, Charsets.US_ASCII)
                    if (keyword == "ccv3" || keyword == "chara") {
                        if (nullIdx + 2 < data.size && data[nullIdx + 1].toInt() == 0) {
                            var p = nullIdx + 3
                            while (p < data.size && data[p].toInt() != 0) p++
                            p++
                            while (p < data.size && data[p].toInt() != 0) p++
                            p++
                            if (p < data.size) {
                                val text = String(data, p, data.size - p, Charsets.UTF_8)
                                if (keyword == "ccv3") foundV3 = text else foundV2 = text
                            }
                        }
                    }
                }
            }
            if (foundV3 != null) break
        }
        val base64 = foundV3 ?: foundV2 ?: return null
        return try {
            String(java.util.Base64.getDecoder().decode(base64), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // ---------- 条目解析 ----------

    private fun parseEntry(o: JsonObject, fallbackIndex: Int): ImportedEntry {
        // keys 兼容：keys / key / keywords；元素可为字符串或 { key, ... }
        val keys = mutableListOf<String>()
        for (kName in listOf("keys", "key", "keywords")) {
            val el = o.get(kName) ?: continue
            if (el.isJsonArray) {
                for (item in el.asJsonArray) {
                    when {
                        item.isJsonPrimitive -> item.asString.trim().takeIf { it.isNotBlank() }?.let { keys.add(it) }
                        item.isJsonObject -> str(item.asJsonObject, "key")?.let { keys.add(it) }
                    }
                }
            }
            if (keys.isNotEmpty()) break
        }
        val content = str(o, "content") ?: str(o, "text") ?: ""
        if (content.isBlank()) throw ImportException("第 ${'$'}{fallbackIndex + 1} 条缺少 content")
        val comment = str(o, "comment") ?: str(o, "title") ?: str(o, "name")
        return ImportedEntry(
            keys = keys,
            content = content,
            comment = comment,
            enabled = bool(o, "enabled") ?: bool(o, "disable")?.not() ?: true,
            constant = bool(o, "constant") ?: bool(o, "always_on") ?: false,
            selective = bool(o, "selective") ?: false,
            insertionOrder = int(o, "insertion_order") ?: int(o, "order") ?: fallbackIndex,
            priority = int(o, "priority") ?: 100,
            position = normalizePosition(o.get("position")),
            caseSensitive = bool(o, "case_sensitive") ?: bool(o, "caseSensitive") ?: false,
            matchMode = str(o, "match_mode")?.lowercase()?.takeIf { it in setOf("any", "all") } ?: "any",
            displayIndex = int(o, "display_index") ?: int(o, "displayIndex") ?: fallbackIndex
        )
    }

    /** ST position 数字（0-6）或字符串归一化为 Nekobot 字符串枚举。 */
    private fun normalizePosition(el: JsonElement?): String = when {
        el == null || el.isJsonNull -> "before_char"
        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> when (el.asInt) {
            0 -> "before_char"
            1 -> "after_char"
            2 -> "before_an"
            3 -> "after_an"
            else -> "at_depth"
        }
        else -> el.asString.trim().lowercase().takeIf {
            it in setOf("before_char", "after_char", "before_an", "after_an", "at_depth")
        } ?: "before_char"
    }

    // ---------- JSON getter 工具 ----------

    private fun str(o: JsonObject, vararg names: String): String? {
        for (n in names) {
            val el = o.get(n) ?: continue
            if (el.isJsonPrimitive && !el.asJsonPrimitive.isJsonNull) {
                return el.asString.trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun bool(o: JsonObject, vararg names: String): Boolean? {
        for (n in names) {
            val el = o.get(n) ?: continue
            if (el.isJsonPrimitive) {
                return try { el.asBoolean } catch (e: Exception) { null }
            }
        }
        return null
    }

    private fun int(o: JsonObject, vararg names: String): Int? {
        for (n in names) {
            val el = o.get(n) ?: continue
            if (el.isJsonPrimitive) {
                return try { el.asInt } catch (e: Exception) { null }
            }
        }
        return null
    }
}
