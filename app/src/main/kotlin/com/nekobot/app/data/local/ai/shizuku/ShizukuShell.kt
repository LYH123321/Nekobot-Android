package com.nekobot.app.data.local.ai.shizuku

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 特权 Shell 工具：让 Agent 能以 Shizuku（ADB/Root）权限执行系统命令。
 * 典型用途：input tap / am start / pm install / pm grant / settings put 等。
 *
 * 使用前提：设备已启动 Shizuku（无线调试或 Root 方式），且已在本 App 授权。
 */
object ShizukuShell {

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        // 0 == PERMISSION_GRANTED
        isAvailable() && Shizuku.checkSelfPermission() == 0
    } catch (e: Throwable) {
        false
    }

    fun statusText(): String = when {
        !isAvailable() -> "Shizuku 未运行：请先在 Shizuku App 中启动服务（无线调试或 Root）"
        !isPermissionGranted() -> "Shizuku 已运行但未授权本应用：请在 Shizuku App 中为 Nekobot 授予权限"
        else -> "ok"
    }

    /** Shizuku.newProcess 为隐藏 API，通过反射调用。 */
    private fun newProcess(cmd: Array<String>): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(null, cmd, null, null) as? Process
    } catch (e: Throwable) {
        null
    }

    /**
     * 以 Shizuku 特权执行 shell 命令，返回 stdout+stderr 合并文本。
     * [timeoutSec] 秒未结束则强杀进程。
     */
    fun run(command: String, timeoutSec: Int = 30): Result {
        if (!isAvailable()) return Result(1, "", "Shizuku 未运行：${statusText()}")
        if (!isPermissionGranted()) return Result(1, "", "Shizuku 未授权：${statusText()}")
        val process = newProcess(arrayOf("sh", "-c", command))
            ?: return Result(1, "", "Shizuku 无法创建进程（newProcess 不可用）")
        return try {
            val stdout = StringBuilder()
            val err = StringBuilder()
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val outThread = Thread {
                try { outReader.forEachLine { stdout.appendLine(it) } } catch (_: Throwable) {}
            }
            val errThread = Thread {
                try { errReader.forEachLine { err.appendLine(it) } } catch (_: Throwable) {}
            }
            outThread.start(); errThread.start()
            val finished = process.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                outThread.join(2000); errThread.join(2000)
                return Result(-1, stdout.toString(), "命令超时（${timeoutSec}s），进程已终止")
            }
            outThread.join(2000); errThread.join(2000)
            val output = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (err.isNotEmpty()) append(err)
            }
            Result(process.exitValue(), output, "")
        } catch (e: Throwable) {
            Result(1, "", "Shizuku 执行失败: ${e.message}")
        }
    }

    data class Result(val exitCode: Int, val output: String, val error: String) {
        val isSuccess: Boolean get() = exitCode == 0 && error.isEmpty()
    }
}