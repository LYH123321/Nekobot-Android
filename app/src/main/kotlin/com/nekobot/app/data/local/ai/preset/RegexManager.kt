package com.nekobot.app.data.local.ai.preset

import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 酒馆式正则（Regex Script）兼容层：Foreverse regex-author 产线格式。
 *
 * 正则 JSON：
 * {
 *   "id":"hide-status","name":"隐藏状态块",
 *   "find":"<status>[\\s\\S]*?</status>","replace":"",
 *   "placement":["AI_OUTPUT"],"markdownOnly":true
 * }
 *
 * - placement: AI_OUTPUT（改 AI 回复显示）/ USER_INPUT（改上行 prompt 中的用户消息）
 * - markdownOnly=true 只影响显示，不改入库内容（当前实现为显示与存储同层，保留字段兼容）
 * - 支持替换串中的 $1..$9 与 {{match}}（整段匹配）
 */
object RegexManager {

    data class RegexRule(
        val id: String,
        val name: String,
        val find: String,
        val replace: String,
        val placement: List<String>,   // AI_OUTPUT / USER_INPUT / WORLD_INFO
        val markdownOnly: Boolean = false,
        val promptOnly: Boolean = false,
        val enabled: Boolean = true
    )

    private val rules = CopyOnWriteArrayList<RegexRule>()

    fun all(): List<RegexRule> = rules.toList()

    fun clear() = rules.clear()

    fun add(rule: RegexRule) {
        rules.removeAll { it.id == rule.id }
        rules.add(rule)
    }

    fun remove(id: String): Boolean = rules.removeIf { it.id == id }

    /** 解析单条或数组 JSON（FO 单条 / ST regex 数组都支持）。 */
    fun parse(json: String): Result<List<RegexRule>> = try {
        val el = JsonParser.parseString(json)
        val arr = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject && el.asJsonObject.has("scripts") -> el.asJsonObject.getAsJsonArray("scripts")
            el.isJsonObject -> com.google.gson.JsonArray().apply { add(el) }
            else -> return Result.failure(IllegalArgumentException("不是 JSON 对象或数组"))
        }
        val out = mutableListOf<RegexRule>()
        for (item in arr) {
            val o = item.asJsonObject
            val find = o.get("findRegex")?.takeIf { it.isJsonPrimitive }?.asString
                ?: o.get("find")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return Result.failure(IllegalArgumentException("某条规则缺少 find 字段"))
            // 校验 pattern 可编译
            Regex(find)
            val replaceRaw = o.get("replaceString")?.takeIf { it.isJsonPrimitive }?.asString
                ?: o.get("replace")?.takeIf { it.isJsonPrimitive }?.asString
                ?: ""
            val placement = mutableListOf<String>()
            val pl = o.get("placement")
            if (pl != null && pl.isJsonArray) {
                for (p in pl.asJsonArray) {
                    when (p.asString.uppercase()) {
                        "AI_OUTPUT" -> placement.add("AI_OUTPUT")
                        "USER_INPUT" -> placement.add("USER_INPUT")
                        "WORLD_INFO" -> placement.add("AI_OUTPUT") // 世界书文本同样在显示层处理
                        "REASONING", "SLASH_COMMAND" -> { /* 忽略不支持的域 */ }
                    }
                }
            }
            if (placement.isEmpty()) placement.add("AI_OUTPUT")
            out.add(
                RegexRule(
                    id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: o.get("scriptName")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: "regex_${System.nanoTime()}",
                    name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: o.get("scriptName")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    find = find,
                    replace = replaceRaw,
                    placement = placement,
                    markdownOnly = o.get("markdownOnly")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    promptOnly = o.get("promptOnly")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    enabled = o.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean?.not() ?: true
                )
            )
        }
        if (out.isEmpty()) return Result.failure(IllegalArgumentException("没有解析到任何规则"))
        Result.success(out)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 对文本应用指定作用域的全部规则。 */
    fun apply(text: String, scope: String): String {
        if (rules.isEmpty()) return text
        var result = text
        for (r in rules) {
            if (!r.enabled || r.scope() != scope) continue
            result = try {
                r.toRegex().replace(result) { m ->
                    r.replace
                        .replace("{{match}}", m.value)
                        .let { rejigDollarGroups(it, m) }
                }
            } catch (e: Throwable) {
                result // 单条规则出错跳过，不影响其余
            }
        }
        return result
    }

    private fun RegexRule.scope(): String = if ("USER_INPUT" in placement) "USER_INPUT" else "AI_OUTPUT"

    private fun RegexRule.toRegex(): Regex = Regex(find, setOf(RegexOption.MULTILINE))

    /** 把替换串中的 $1..$9 翻译为对应捕获组（MatchResult 已用 {{match}} 处理整段）。 */
    private fun rejigDollarGroups(template: String, m: MatchResult): String {
        var out = template
        for (i in 1..9) {
            val group = m.groups.getOrNull(i)?.value ?: ""
            out = out.replace("$$i", group)
        }
        return out
    }

    /** 试运行：返回示例文本应用规则后的结果（供 regex_test 工具）。 */
    fun test(sample: String): String = apply(sample, "AI_OUTPUT")

    fun stats(): String = "rules=${rules.size} aiOutput=${rules.count { "AI_OUTPUT" in it.placement }} userInput=${rules.count { "USER_INPUT" in it.placement }}"
}