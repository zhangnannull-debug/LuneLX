package com.lunelx.music.lx.store

/**
 * 判断一段文本是否是合法的 lx 音源脚本。
 *
 * 独立成对象的原因：这段逻辑曾因只认 `lx_setup` 字面量，把大量旧版脚本
 * 误判为非法导致导入失败；把它抽出来才能写回归测试。
 *
 * ## lx-music 的两代脚本 API
 *
 * **新版**：脚本直接定义 `lx_setup`，由 preload 调用完成注册。
 * ```js
 * globalThis.lx_setup = (key, id, name, ...) => { ... }
 * ```
 *
 * **旧版**：从 `globalThis.lx` 解构事件表与通信函数，再用 `on()` 注册处理器。
 * ```js
 * const { EVENT_NAMES, request, on, send } = globalThis['lx']
 * on(EVENT_NAMES.request, ({ source, action, info }) => { ... })
 * ```
 *
 * 两代都必须接受。此外脚本常被混淆，`globalThis['lx']` 与 `globalThis.lx`
 * 两种写法都要认。
 */
object LxScriptDetector {

    /**
     * 判定脚本是否属于 lx 音源。
     *
     * 采用「命中任一标记即可」的宽松策略：宁可放过一个可疑脚本
     * （加载失败时引擎会报错），也不要误杀合法脚本。
     */
    fun looksLikeLxScript(content: String): Boolean {
        if (content.isBlank()) return false
        return MARKERS.any { content.contains(it) }
    }

    private val MARKERS = listOf(
        // 新版注册入口
        "lx_setup",
        // 两代共有的事件名表
        "EVENT_NAMES",
        // 旧版取 lx 对象的三种写法
        "globalThis.lx",
        "globalThis['lx']",
        "globalThis[\"lx\"]",
        // 旧版 API 调用
        "lx.on(",
        "lx.send(",
        "lx.request(",
        // 兜底：直接解构（少见但合法）
        "{EVENT_NAMES",
        "{ EVENT_NAMES",
    )
}
