package com.lunelx.music.lx.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * 各平台 Cookie 的统一存储。
 *
 * 本项目**不内置**任何平台的官方 API 直连，Cookie 只用于：
 * 1. 注入到音源脚本的 `@wy_cookie` / `@tx_cookie` / `@kg_cookie` / `@kw_cookie` 头，
 *    让脚本自己携带登录态去请求；
 * 2. 流代理转发时附加 Cookie。
 *
 * 因此这里只需要最朴素的 key-value，不需要 OLX 里那套 VIP 类型、昵称、头像等账号体系。
 */
class LxCookieStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lx_cookies", Context.MODE_PRIVATE)

    fun get(source: String): String? =
        prefs.getString(keyOf(source), null)?.takeIf { it.isNotBlank() }

    fun set(source: String, cookie: String?) {
        prefs.edit().apply {
            if (cookie.isNullOrBlank()) remove(keyOf(source)) else putString(keyOf(source), cookie)
        }.apply()
    }

    fun has(source: String): Boolean = get(source) != null

    fun clear(source: String) = set(source, null)

    fun clearAll() = prefs.edit().clear().apply()

    /** 返回所有已登录平台，用于 UI 展示。 */
    fun loggedInSources(): Set<String> =
        LxSources.ALL.filter { has(it) }.toSet()

    private fun keyOf(source: String) = "cookie_$source"
}
