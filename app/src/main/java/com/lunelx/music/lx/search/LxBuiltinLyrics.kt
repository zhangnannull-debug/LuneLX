package com.lunelx.music.lx.search

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lunelx.music.lx.engine.LxLog
import com.lunelx.music.lx.engine.LxLyricResult
import com.lunelx.music.lx.engine.LxSources
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Base64

/**
 * 内置的平台歌词。
 *
 * ## 为什么需要
 * 和搜索同理：lx 音源脚本的 `actions` 里通常只有 `musicUrl`（「裤佬音源」的
 * `lyric`/`pic` 出现次数都是 0），歌词只能由客户端自己取。
 *
 * ## 各平台可用性（实测）
 * | 源 | 方式 | 状态 |
 * |---|---|---|
 * | wy | `api/song/lyric` 返回 `lrc.lyric` + `tlyric.lyric` | ✅ 含翻译 |
 * | tx | `fcg_query_lyric_new.fcg` 需 Referer，返回 `lyric` | ✅ |
 * | kg | 两步：`krcs.kugou.com/search` 取 id/accesskey → `lyrics.kugou.com/download` 得 base64 | ✅ |
 * | mg | 搜索结果自带 `lyricUrl`，直接 GET 就是纯文本 LRC | ✅ |
 * | kw | 所有歌词入口都返回 `TP=ERROR REQUEST` / 301（已封） | ❌ 返回 null |
 */
object LxBuiltinLyrics {

    fun isSupported(source: String): Boolean = source != LxSources.KUWO && source in SUPPORTED

    private val SUPPORTED = setOf(
        LxSources.KUGOU, LxSources.NETEASE, LxSources.TENCENT, LxSources.MIGU,
    )

    /**
     * 取歌词。返回 null 表示该平台不支持或没取到。
     *
     * @param songmid 平台内 id（wy=数字 id、tx=songmid、kg=文件 hash、mg=copyrightId）
     * @param extra   搜索阶段额外带下来的字段（咪咕的 `lyricUrl` 就在这里）
     */
    fun lyric(
        client: OkHttpClient,
        source: String,
        songmid: String,
        extra: Map<String, String> = emptyMap(),
    ): LxLyricResult? = when (source) {
        LxSources.NETEASE -> lyricNetease(client, songmid)
        LxSources.TENCENT -> lyricTencent(client, songmid)
        LxSources.KUGOU -> lyricKugou(client, songmid)
        LxSources.MIGU -> lyricMigu(client, extra["lyricUrl"])
        else -> null
    }

    // ---------------------------------------------------------------- 网易云

    private fun lyricNetease(client: OkHttpClient, songmid: String): LxLyricResult? {
        val url = "https://music.163.com/api/song/lyric?id=$songmid&lv=1&kv=1&tv=-1"
        val root = fetchJson(client, url, "https://music.163.com/") ?: return null
        val lrc = root.obj("lrc")?.str("lyric")?.takeIf { it.isNotBlank() } ?: return null
        val tlyric = root.obj("tlyric")?.str("lyric")?.takeIf { it.isNotBlank() }
        return LxLyricResult(lyric = lrc, tlyric = tlyric)
    }

    // ---------------------------------------------------------------- QQ

    private fun lyricTencent(client: OkHttpClient, songmid: String): LxLyricResult? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$songmid&format=json&nobase64=1&g_tk=5381"
        // 这个接口对 Referer 很敏感，不带会返回空
        val root = fetchJson(client, url, "https://y.qq.com/portal/player.html") ?: return null
        val lyric = root.str("lyric")?.takeIf { it.isNotBlank() } ?: return null
        val trans = root.str("trans")?.takeIf { it.isNotBlank() }
        return LxLyricResult(lyric = lyric, tlyric = trans)
    }

    // ---------------------------------------------------------------- 酷狗（两步）

    private fun lyricKugou(client: OkHttpClient, hash: String): LxLyricResult? {
        val searchUrl = "https://krcs.kugou.com/search" +
            "?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash"
        val candidates = fetchJson(client, searchUrl, "https://www.kugou.com/")
            ?.arr("candidates") ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val id = first.str("id") ?: return null
        val accessKey = first.str("accesskey") ?: return null

        val downloadUrl = "https://lyrics.kugou.com/download" +
            "?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val body = fetchJson(client, downloadUrl, "https://www.kugou.com/") ?: return null
        val content = body.str("content") ?: return null
        // 返回的是 base64 编码的 LRC 文本
        val text = runCatching {
            String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
        }.onFailure { LxLog.w(it, "LxBuiltinLyrics: kugou base64 decode failed") }
            .getOrNull() ?: return null
        return text.takeIf { it.isNotBlank() }?.let { LxLyricResult(lyric = it) }
    }

    // ---------------------------------------------------------------- 咪咕

    /** 咪咕搜索结果里已经带了 `lyricUrl`，直接取纯文本即可。 */
    private fun lyricMigu(client: OkHttpClient, lyricUrl: String?): LxLyricResult? {
        if (lyricUrl.isNullOrBlank()) return null
        val request = Request.Builder()
            .url(lyricUrl)
            .header("User-Agent", UA)
            .header("Referer", "https://music.migu.cn/")
            .get()
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        return text.takeIf { it.isNotBlank() }?.let { LxLyricResult(lyric = it) }
    }

    // ---------------------------------------------------------------- HTTP

    private fun fetchJson(client: OkHttpClient, url: String, referer: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LxLog.w("LxBuiltinLyrics: HTTP ${resp.code} for ${url.take(80)}")
                    return null
                }
                resp.body?.string()
            }
        }.onFailure { LxLog.w(it, "LxBuiltinLyrics: request failed ${url.take(80)}") }.getOrNull()
            ?: return null
        return runCatching { JsonParser.parseString(text).asJsonObject }
            .onFailure { LxLog.w(it, "LxBuiltinLyrics: json parse failed ${url.take(80)}") }
            .getOrNull()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String) =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
