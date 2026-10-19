package com.lunelx.music.lx.discover

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lunelx.music.lx.engine.LxLog
import com.lunelx.music.lx.engine.LxSong
import com.lunelx.music.lx.engine.LxSources
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 某个平台的「发现」条目 —— 目前就是各平台的官方榜单（排行榜）。
 *
 * 为什么不叫 `LxBoard`：同一个位置将来还要放「推荐歌单 / 新歌速递」等，
 * 名字留宽一点，UI 只需要知道"点进去会有一串歌"。
 */
data class LxDiscoverBoard(
    val id: String,
    val source: String,
    val name: String,
    val cover: String? = null,
    /** 副标题：更新频率 / 播放量之类，取不到就为 null。 */
    val subtitle: String? = null,
)

/**
 * 内置的「发现」数据（各平台公开榜单）。
 *
 * ## 为什么又是内置
 * 音源脚本只实现 `musicUrl`，没有 search / 榜单能力（见 [com.lunelx.music.lx.search.LxBuiltinSearch]）。
 * 想在「在线音乐」页里"逛"平台内容，只能客户端自己调平台接口。
 *
 * ## 各平台现状（本机实测，2026-10）
 * | 源 | 榜单列表 | 榜单歌曲 | 备注 |
 * |---|---|---|---|
 * | kg | `mobilecdn.kugou.com/api/v3/rank/list` | `/rank/song` | ✅ 26 个榜，含 TOP500/飙升榜 |
 * | wy | `music.163.com/api/toplist` | `api/playlist/detail?id=` | ✅ 官方榜（热歌/新歌/飙升…） |
 * | tx | `c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg` | `fcg_v8_toplist_cp.fcg` | ✅ 巅峰榜系列 |
 * | kw | 需 `kw_token` cookie + `csrf` 头 | — | ❌ 匿名请求返回 "The request is illegal" |
 * | mg | 未找到无签名可用的公开榜单接口 | — | ❌ 现有路径均返回「路由请求不支持」 |
 *
 * 所以 [isSupported] 只对前三个返回 true；kw/mg 在 UI 上显示为「该平台暂未提供发现数据」，
 * 但仍可以走搜索播放（[com.lunelx.music.lx.search.LxBuiltinSearch]）。
 *
 * 这里的函数都是阻塞 IO，调用方负责切到 IO 线程（[com.lunelx.music.lx.LxRepository] 已包好）。
 */
object LxDiscover {

    /** 该平台有没有可用的发现数据。 */
    fun isSupported(source: String): Boolean = source in SUPPORTED

    private val SUPPORTED = setOf(LxSources.KUGOU, LxSources.NETEASE, LxSources.TENCENT)

    // ---------------------------------------------------------------- 榜单列表

    fun boards(client: OkHttpClient, source: String): List<LxDiscoverBoard> = when (source) {
        LxSources.KUGOU -> boardsKugou(client)
        LxSources.NETEASE -> boardsNetease(client)
        LxSources.TENCENT -> boardsTencent(client)
        else -> emptyList()
    }

    private fun boardsKugou(client: OkHttpClient): List<LxDiscoverBoard> {
        // ⚠️ 必须是 http：`mobilecdn.kugou.com` 的证书是 `*.cdn.myqcloud.com` 的，
        // 走 https 会直接 SSLPeerUnverifiedException（真机实测）。
        // 全局已放行明文（res/xml/network_security_config.xml 的 base-config）。
        val url = "http://mobilecdn.kugou.com/api/v3/rank/list" +
            "?withsong=0&showtype=2&plat=0&parentid=0&apiver=3"
        val info = fetchJson(client, url, "http://www.kugou.com/")
            ?.obj("data")?.arr("info") ?: return emptyList()
        return info.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("rankid") ?: return@mapNotNull null
            val name = o.str("rankname")?.clean() ?: return@mapNotNull null
            LxDiscoverBoard(
                id = id,
                source = LxSources.KUGOU,
                name = name,
                cover = (o.str("img_9") ?: o.str("img_cover") ?: o.str("banner_9"))
                    ?.replace("{size}", "400"),
                subtitle = buildSubtitle(
                    update = o.str("update_frequency"),
                    playTimes = o.longOrZero("play_times"),
                ),
            )
        }
    }

    private fun boardsNetease(client: OkHttpClient): List<LxDiscoverBoard> {
        val url = "https://music.163.com/api/toplist"
        val list = fetchJson(client, url, "https://music.163.com/")?.arr("list") ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: o.str("toplistId") ?: return@mapNotNull null
            val name = o.str("name") ?: o.str("title") ?: return@mapNotNull null
            LxDiscoverBoard(
                id = id,
                source = LxSources.NETEASE,
                name = name.clean(),
                cover = (o.str("coverImgUrl") ?: o.str("coverUrl"))?.https(),
                subtitle = o.str("updateFrequency")?.clean(),
            )
        }
    }

    private fun boardsTencent(client: OkHttpClient): List<LxDiscoverBoard> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg" +
            "?format=json&g_tk=5381&uin=0&needNewCode=1&platform=h5"
        val list = fetchJson(client, url, "https://y.qq.com/")?.obj("data")?.arr("topList")
            ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val name = o.str("topTitle") ?: o.str("title") ?: return@mapNotNull null
            LxDiscoverBoard(
                id = id,
                source = LxSources.TENCENT,
                name = name.clean(),
                cover = o.str("picUrl")?.https(),
                subtitle = o.str("updateFrequency")?.let { "更新：$it" }
                    ?: o.longOrZero("listenCount").takeIf { it > 0 }?.let { "${it / 10_000} 万播放" },
            )
        }
    }

    // ---------------------------------------------------------------- 榜单歌曲

    fun boardSongs(
        client: OkHttpClient,
        source: String,
        boardId: String,
        limit: Int = 60,
    ): List<LxSong> = when (source) {
        LxSources.KUGOU -> songsKugou(client, boardId, limit)
        LxSources.NETEASE -> songsNetease(client, boardId, limit)
        LxSources.TENCENT -> songsTencent(client, boardId, limit)
        else -> emptyList()
    }

    private fun songsKugou(client: OkHttpClient, boardId: String, limit: Int): List<LxSong> {
        // 与榜单列表同一个域名，同样只能走 http（证书不匹配）。
        // ⚠️ `version=8000` 是必须的：不带它时接口只回 total=1~4 的「榜内变动」，
        // 带上才是完整榜单（TOP500 → total=500）。
        val url = "http://mobilecdn.kugou.com/api/v3/rank/song" +
            "?rankid=${enc(boardId)}&page=1&pagesize=$limit&plat=0&apiver=3&version=8000"
        val info = fetchJson(client, url, "http://www.kugou.com/")
            ?.obj("data")?.arr("info") ?: return emptyList()
        return info.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val hash = o.str("hash") ?: return@mapNotNull null
            // filename 形如 "印子月 - 落空"；songname 更干净，优先用它
            val name = (o.str("songname") ?: o.str("filename"))?.clean() ?: return@mapNotNull null
            val singer = o.arr("authors")
                ?.mapNotNull { (it as? JsonObject)?.str("author_name") }
                ?.joinToString("/")
                .orEmpty()
                .ifBlank {
                    // filename 里 "-" 前面那截就是歌手，兜底用
                    o.str("filename")?.substringBefore(" - ").orEmpty().trim()
                }
            LxSong(
                source = LxSources.KUGOU,
                songmid = hash,
                name = name,
                singer = singer,
                albumName = o.str("album_name").orEmpty(),
                interval = o.longOrZero("duration"),
                // ⚠️ 榜单歌曲的 `album_cover` / `cover` 恒为 null（实测），
                // 真正带图的是 `album_sizable_cover`（含 {size} 占位）。
                // 少了这一条，发现页的榜单歌曲列表就全是空封面。
                pic = (o.str("album_sizable_cover") ?: o.str("album_cover") ?: o.str("cover"))
                    ?.replace("{size}", "400"),
                qualitys = listOf("128k"),
                hash = hash,
            )
        }
    }

    private fun songsNetease(client: OkHttpClient, boardId: String, limit: Int): List<LxSong> {
        val url = "https://music.163.com/api/playlist/detail?id=${enc(boardId)}"
        val tracks = fetchJson(client, url, "https://music.163.com/")
            ?.obj("result")?.arr("tracks") ?: return emptyList()
        return tracks.take(limit).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val name = o.str("name")?.clean() ?: return@mapNotNull null
            // 老接口给 artists+album+duration(ms)，新接口给 ar+al+dt，两套都认
            val singer = (o.arr("artists") ?: o.arr("ar"))
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                ?.joinToString("/")
                .orEmpty()
            val albumObj = o.obj("album") ?: o.obj("al")
            val durationMs = o.longOrZero("duration").takeIf { it > 0 } ?: o.longOrZero("dt")
            LxSong(
                source = LxSources.NETEASE,
                songmid = id,
                name = name,
                singer = singer,
                albumName = albumObj?.str("name").orEmpty(),
                interval = durationMs / 1000L,
                pic = albumObj?.str("picUrl")?.https(),
                qualitys = listOf("128k"),
            )
        }
    }

    private fun songsTencent(client: OkHttpClient, boardId: String, limit: Int): List<LxSong> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg" +
            "?topid=${enc(boardId)}&format=json&page=detail&type=top" +
            "&song_begin=0&song_num=$limit&g_tk=5381&platform=h5"
        val list = fetchJson(client, url, "https://y.qq.com/")?.arr("songlist") ?: return emptyList()
        return list.mapNotNull { el ->
            val wrapper = el as? JsonObject ?: return@mapNotNull null
            // 榜单接口把歌套在 "data" 里
            val o = wrapper.obj("data") ?: wrapper
            val mid = o.str("songmid") ?: return@mapNotNull null
            val name = o.str("songname")?.clean() ?: return@mapNotNull null
            val albumMid = o.str("albummid")
            LxSong(
                source = LxSources.TENCENT,
                songmid = mid,
                name = name,
                singer = o.arr("singer")
                    ?.mapNotNull { (it as? JsonObject)?.str("name") }
                    ?.joinToString("/")
                    .orEmpty(),
                albumName = o.str("albumname").orEmpty(),
                interval = o.longOrZero("interval"),
                pic = albumMid?.takeIf { it.isNotBlank() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
                qualitys = listOf("128k"),
            )
        }
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
                    LxLog.w("LxDiscover: HTTP ${resp.code} for ${url.take(80)}")
                    return null
                }
                resp.body?.string()
            }
        }.onFailure { LxLog.w(it, "LxDiscover: request failed ${url.take(80)}") }.getOrNull()
            ?: return null
        return runCatching { JsonParser.parseString(text).asJsonObject }
            .onFailure { LxLog.w(it, "LxDiscover: json parse failed ${url.take(80)}") }
            .getOrNull()
    }

    private fun buildSubtitle(update: String?, playTimes: Long): String? {
        val parts = mutableListOf<String>()
        update?.takeIf { it.isNotBlank() }?.let { parts.add("更新：$it") }
        if (playTimes > 0) parts.add("${playTimes / 10_000} 万播放")
        return parts.joinToString(" · ").ifBlank { null }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.longOrZero(key: String): Long {
        val p = get(key)?.takeIf { it.isJsonPrimitive } ?: return 0L
        return runCatching { p.asLong }.getOrElse {
            runCatching { p.asString.toDouble().toLong() }.getOrDefault(0L)
        }
    }

    private fun String.clean(): String = replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    private fun String.https(): String = replaceFirst("http://", "https://")
}
