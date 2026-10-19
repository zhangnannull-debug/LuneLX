package com.lunelx.music.lx.store

import android.content.Context
import android.content.SharedPreferences
import com.lunelx.music.lx.engine.LxLog
import com.lunelx.music.lx.engine.LxScriptMeta
import java.io.File

/**
 * 音源脚本的落盘存储。
 *
 * 目录布局（应用私有目录，无需任何存储权限）：
 * ```
 * files/lx_scripts/
 * ├── <脚本名>.js        ← 脚本文本
 * └── index.json        ← 元信息与启用状态
 * ```
 *
 * 之所以不用 assets 而是私有目录：脚本是用户导入的，随时可增删，
 * 且导入来源可以是本地文件或 URL。
 */
class LxScriptStore(context: Context) {

    private val dir: File = File(context.applicationContext.filesDir, "lx_scripts").apply {
        if (!exists()) mkdirs()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lx_scripts_state", Context.MODE_PRIVATE)

    /** 列出所有已导入脚本。按名字排序，保证 UI 稳定。 */
    fun listScripts(): List<LxScriptMeta> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".js") } ?: return emptyList()
        return files.map { f ->
            val parsed = parseHeader(f.readText())
            LxScriptMeta(
                assetPath = f.absolutePath,
                name = parsed.name.ifBlank { f.nameWithoutExtension },
                version = parsed.version,
                author = parsed.author,
                description = parsed.description,
                isImported = true,
            )
        }.sortedBy { it.name }
    }

    /**
     * 导入脚本。文件名冲突时自动加序号。
     *
     * @return 成功返回元信息；内容不合法返回 null
     */
    fun importScript(fileName: String, content: String): LxScriptMeta? {
        if (content.isBlank()) {
            LxLog.w("LxScriptStore: rejected empty script $fileName")
            return null
        }
        if (!looksLikeLxScript(content)) {
            LxLog.w("LxScriptStore: rejected non-lx script $fileName")
            return null
        }

        val safeName = sanitize(fileName).ifBlank { "script" }
        var target = File(dir, if (safeName.endsWith(".js")) safeName else "$safeName.js")
        var i = 1
        while (target.exists()) {
            val base = safeName.removeSuffix(".js")
            target = File(dir, "${base}_${i}.js")
            i++
        }

        return runCatching {
            target.writeText(content)
            val parsed = parseHeader(content)
            LxScriptMeta(
                assetPath = target.absolutePath,
                name = parsed.name.ifBlank { target.nameWithoutExtension },
                version = parsed.version,
                author = parsed.author,
                description = parsed.description,
                isImported = true,
            )
        }.onFailure { LxLog.e(it, "LxScriptStore: write failed") }.getOrNull()
    }

    /** 删除脚本文件及其启用状态。 */
    fun deleteScript(assetPath: String) {
        runCatching { File(assetPath).delete() }
        prefs.edit().remove(keyEnabled(assetPath)).apply()
    }

    fun isEnabled(assetPath: String): Boolean = prefs.getBoolean(keyEnabled(assetPath), true)

    fun setEnabled(assetPath: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(assetPath), enabled).apply()
    }

    /**
     * 在线音源总开关。
     *
     * 落盘而不是放内存：否则进程重启后用户「已开启」的选择会静默丢失，
     * 再进在线页只会看到「还没有可用的音源脚本」。
     */
    fun isOnlineEnabled(): Boolean = prefs.getBoolean(KEY_ONLINE_ENABLED, false)

    fun setOnlineEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_ENABLED, enabled).apply()
    }

    // ------------------------------------------------------------ 头部解析

    /**
     * 判断一段文本是否是 lx 音源脚本。
     *
     * lx-music 有两代脚本 API，两者都必须接受：
     *
     * 1. **新版**：脚本直接定义 `lx_setup`，由 preload 调用它完成注册。
     * 2. **旧版**：脚本从 `globalThis.lx` 解构 `{ EVENT_NAMES, request, on, send }`，
     *    然后调用 `on(EVENT_NAMES.request, ...)` 注册请求处理器。
     *
     * 早期实现只检查 `lx_setup` 字面量，导致大量旧版脚本（例如混淆压缩过的
     * 「裤佬音源」）被误判为非法而导入失败。这里改为检查**实际用到的符号**。
     *
     * 另外脚本常被混淆，`globalThis['lx']` 与 `globalThis.lx` 两种写法都要认。
     */
    private fun looksLikeLxScript(content: String): Boolean =
        LxScriptDetector.looksLikeLxScript(content)


    private data class ScriptHeader(
        val name: String = "",
        val version: String = "",
        val author: String = "",
        val description: String = "",
    )

    /**
     * 解析脚本头部的注释块。
     *
     * lx 脚本通常把元信息写在开头的块注释里，形如
     * `@name 某音源`、`@version 1.0.0`、`@author xxx`、`@description 说明`。
     * 也兼容行注释写法 `// @name ...`。
     */
    private fun parseHeader(content: String): ScriptHeader {
        val head = content.take(4000)
        fun field(key: String): String {
            val regex = Regex("""@$key\s+(.+)""")
            return regex.find(head)?.groupValues?.get(1)?.trim()?.trimEnd('*', '/', ' ') ?: ""
        }
        return ScriptHeader(
            name = field("name"),
            version = field("version"),
            author = field("author"),
            description = field("description"),
        )
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)

    private fun keyEnabled(assetPath: String) = "enabled_${assetPath.hashCode()}"

    private companion object {
        const val KEY_ONLINE_ENABLED = "online_enabled"
    }
}
