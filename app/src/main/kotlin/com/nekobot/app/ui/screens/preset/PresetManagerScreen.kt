package com.nekobot.app.ui.screens.preset

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.preset.PresetManager
import com.nekobot.app.data.local.ai.preset.RegexManager

/**
 * 酒馆预设 + 正则管理页：
 * - 从文件选择器导入预设 JSON（blocks[] 或 ST prompts[] 格式）
 * - 一键启用 / 停用当前预设
 * - 导入正则 JSON（单条或数组）
 * - 显示当前状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(loadStatus()) }
    var message by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<PresetManager.PresetBlock?>(null) }

    fun refresh() { status = loadStatus(); message = null }

    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val r = PresetManager.parse(json)
                if (r.isSuccess) {
                    val p = r.getOrThrow()
                    PresetManager.activate(p)
                    val prefs = ServiceContainer.appContext?.getSharedPreferences("nekobot_preset", 0)
                    prefs?.edit()?.putString("active_preset", json)?.apply()
                    message = "已导入并启用预设：${p.name}（${p.blocks.size} 块）"
                } else {
                    message = "解析失败：${r.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                message = "读取失败：${e.message}"
            }
            refresh()
        }
    }
    val regexPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val r = importRulesVia(RegexManager, json)
                message = if (r.isSuccess) "已导入 ${r.getOrThrow()} 条正则规则" else "解析失败：${r.exceptionOrNull()?.message}"
            } catch (e: Exception) {
                message = "读取失败：${e.message}"
            }
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预设与正则管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }

            // ---- 预设区 ----
            Text("酒馆预设", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (status.presetActive) "当前预设：${status.presetName}（${status.presetBlocks} 块，启用 ${enabledCount()}）" else "未启用预设")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { presetPicker.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                            Icon(Icons.Filled.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("导入预设")
                        }
                        if (status.presetActive) {
                            OutlinedButton(onClick = {
                                PresetManager.deactivate()
                                ServiceContainer.appContext?.getSharedPreferences("nekobot_preset", 0)
                                    ?.edit()?.remove("active_preset")?.apply()
                                refresh()
                            }) { Text("停用") }
                        }
                    }
                    Text("支持 Foreverse blocks[] 与 SillyTavern prompts[] 两种格式；点块名查看内容，开关单独启停每个块。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- 块列表（开关 + 查看内容）----
            if (status.presetActive) {
                Text("块列表", style = MaterialTheme.typography.titleMedium)
                PresetManager.activePreset?.blocks?.forEach { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { viewing = b },
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    (if (b.marker) "[marker] " else "") + (if (b.name.isNotBlank()) b.name else b.identifier),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                if (!b.marker && b.content.isNotBlank()) {
                                    Text(
                                        b.content.take(60).replace('\n', ' ') + if (b.content.length > 60) "…" else "",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = b.enabled,
                                onCheckedChange = { on ->
                                    toggleBlock(b.identifier, on)
                                    refresh()
                                }
                            )
                        }
                    }
                }
            }

            // ---- 正则区 ----
            Text("正则规则", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (status.regexCount > 0) "已加载 ${status.regexCount} 条规则" else "无正则规则")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { regexPicker.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                            Icon(Icons.Filled.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("导入正则")
                        }
                        if (status.regexCount > 0) {
                            OutlinedButton(onClick = { RegexManager.clear(); refresh() }) {
                                Icon(Icons.Filled.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("清空")
                            }
                        }
                    }
                    RegexManager.all().forEach { r ->
                        Text("• ${r.name}  [${if (r.enabled) "启用" else "停用"}]  find=${r.find.take(40)}", fontSize = 12.sp)
                    }
                    Text("placement 为 AI_OUTPUT 的规则改写 AI 回复；USER_INPUT 的规则改写用户输入。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- 块内容查看弹窗 ----
        viewing?.let { b ->
            AlertDialog(
                onDismissRequest = { viewing = null },
                title = { Text(if (b.name.isNotBlank()) b.name else b.identifier) },
                text = {
                    Text(
                        if (b.marker) "[marker 块，由原生管线处理]\nidentifier: ${b.identifier}" else b.content.ifBlank { "（空内容）" },
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewing = null }) { Text("关闭") }
                }
            )
        }
    }
}

private data class Status(val presetActive: Boolean, val presetName: String, val presetBlocks: Int, val regexCount: Int)

private fun loadStatus(): Status {
    val p = PresetManager.activePreset
    val saved = ServiceContainer.appContext?.getSharedPreferences("nekobot_preset", 0)
        ?.getString("active_preset", null)
    if (p == null && !saved.isNullOrBlank()) {
        PresetManager.parse(saved).getOrNull()?.let { PresetManager.activate(it) }
    }
    val cur = PresetManager.activePreset
    return Status(cur != null, cur?.name ?: "", cur?.blocks?.size ?: 0, RegexManager.all().size)
}

private fun importRulesVia(rm: RegexManager, json: String): Result<Int> {
val r = rm.parse(json)
    return if (r.isSuccess) {
        r.getOrThrow().forEach { rm.add(it) }
        Result.success(r.getOrThrow().size)
    } else Result.failure(r.exceptionOrNull()!!)
}
/** 当前启用块数。 */
private fun enabledCount(): Int =
    (PresetManager.activePreset ?: loadStatusRestored())?.blocks?.count { !it.marker && it.enabled } ?: 0
private fun loadStatusRestored() =
    PresetManager.activePreset ?: ServiceContainer.appContext
        ?.getSharedPreferences("nekobot_preset", 0)?.getString("active_preset", null)
        ?.let { PresetManager.parse(it).getOrNull()?.also { PresetManager.activate(it) } }
/** 开关块并持久化。 */
private fun toggleBlock(identifier: String, enable: Boolean) {
    val preset = loadStatusRestored() ?: return
    val updated = preset.copy(blocks = preset.blocks.map {
        if (it.identifier == identifier) it.copy(enabled = enable) else it
    })
    PresetManager.activate(updated)
    ServiceContainer.appContext?.getSharedPreferences("nekobot_preset", 0)
        ?.edit()?.putString("active_preset", PresetManager.serialize(updated))?.apply()
}
