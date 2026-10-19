package com.lunelx.music.lx.search

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lunelx.music.lx.engine.LxLog
import com.lunelx.music.lx.engine.LxSong
import com.lunelx.music.lx.engine.LxSources
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 内置的平台搜索（方案 B）。
 *
 * ## 为什么需要
 * lx 音源脚本的 `actions` 里**从来没有** `search`：在 lx-music 里搜索是客户端自己调各平台
 * 公开接口完成的，脚本只负责把 songmid 换成直链（`musicUrl`）。本项目只跑脚本，
 * 于是像「裤佬音源」这类脚本只能「按 ID 播放」。这里补上客户端搜索。
 *
 * 产出的 [LxSong] 直接交给 `LxRepository.search` 的原有流程，UI / 取流 / 播放都不用改。
 *
 * ## 各平台要点（都是实测字段，不是猜的）
 * | 源 | 接口 | songmid | 封面 |
 * |---|---|---|---|
 * | kg | `songsearch.kugou.com/song_search_v2` | FileHash | `AlbumImage`（`{size}` 占位） |
 * | wy | `music.163.com/api/cloudsearch/pc` | 数字 id | `al.picUrl` |
 * | tx | `c.y.qq.com/soso/fcgi-bin/client_search_cp` | songmid | `y.gtimg.cn/.../T002R300x300M000{albummid}.jpg` |
 * | kw | `search.kuwo.cn/r.s`（**伪 JSON**，单引号） | `MUSICRID` 去掉前缀 | `img1.kuwo.cn/star/albumcover/{PICPATH}` |
 * | mg | `app.c.nf.migu.cn/.../search_all.do` | copyrightId | `imgItems[0].img` |
 *
 * 注意：**只负责搜到 songmid + hash + 封面**，能不能播取决于有没有对应音源的脚本。
 */
object LxBuiltinSearch {

    fun isSupported(source: String): Boolean = source in SUPPORTED

    private val SUPPORTED = setOf(
        LxSources.KUGOU, LxSources.NETEASE, LxSources.TENCENT, LxSources.KUWO, LxSources.MIGU,
    )

    /**
     * 按音源搜索。返回 null 表示该音源没有内置搜索；空列表表示搜到了但无结果。
     * 调用方需保证已在 IO 线程（这里是阻塞 IO）。
     */
    fun search(
        client: OkHttpClient,
        source: String,
        keyword: String,
        page: Int = 1,
        pageSize: Int = 30,
    ): List<LxSong>? = when (source) {
        LxSources.KUGOU -> searchKugou(client, keyword, page, pageSize)
        LxSources.NETEASE -> searchNetease(client, keyword, page, pageSize)
        LxSources.TENCENT -> searchTencent(client, keyword, page, pageSize)
        LxSources.KUWO -> searchKuwo(client, keyword, page, pageSize)
        LxSources.MIGU -> searchMigu(client, keyword, page, pageSize)
        else -> null
    }

    // ---------------------------------------------------------------- 酷狗

    private fun searchKugou(client: OkHttpClient, keyword: String, page: Int, pageSize: Int): List<LxSong> {
        val url = "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=${enc(keyword)}&page=${page.coerceAtLeast(1)}&pagesize=$pageSize" +
            "&platform=WebFilter&format=json&userid=-1&clientver=2000"
        val lists = fetchJson(client, url, "https://www.kugou.com/")?.obj("data")?.arr("lists")
            ?: return emptyList()
        return lists.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val hash = o.str("FileHash") ?: return@mapNotNull null
            if (hash.isBlank()) return@mapNotNull null
            LxSong(
                source = LxSources.KUGOU,
                songmid = hash,          // 酷狗侧 musicUrl 要的就是文件 hash
                name = o.str("SongName")?.clean() ?: return@mapNotNull null,
                singer = o.str("SingerName")?.clean().orEmpty(),
                albumName = o.str("AlbumName")?.clean().orEmpty(),
                interval = o.long("Duration"),
                // 实测：AlbumImage 常为空串，真正有值的是 Image（带 {size} 占位）
                pic = (o.str("Image") ?: o.str("AlbumImage"))
                    ?.replace("{size}", "400")?.https(),
                qualitys = listOf("128k"),
                hash = hash,
            )
        }
    }

    // ---------------------------------------------------------------- 网易云

    private fun searchNetease(client: OkHttpClient, keyword: String, page: Int, pageSize: Int): List<LxSong> {
        val offset = (page.coerceAtLeast(1) - 1) * pageSize
        // cloudsearch 直接返回封面（al.picUrl）与毫秒时长（dt），比 search/get 少一次请求
        val url = "https://music.163.com/api/cloudsearch/pc" +
            "?s=${enc(keyword)}&type=1&limit=$pageSize&offset=$offset"
        val songs = fetchJson(client, url, "https://music.163.com/")
            ?.obj("result")?.arr("songs") ?: return emptyList()
        return songs.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            LxSong(
                source = LxSources.NETEASE,
                songmid = id,
                name = o.str("name")?.clean() ?: return@mapNotNull null,
                singer = o.arr("ar")?.joinToString("/") {
                    (it as? JsonObject)?.str("name").orEmpty()
                }.orEmpty(),
                albumName = o.obj("al")?.str("name").orEmpty(),
                interval = o.long("dt") / 1000L,
                pic = o.obj("al")?.str("picUrl")?.https(),
                qualitys = listOf("128k"),
            )
        }
    }

    // ---------------------------------------------------------------- QQ

    private fun searchTencent(client: OkHttpClient, keyword: String, page: Int, pageSize: Int): List<LxSong> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?p=${page.coerceAtLeast(1)}&n=$pageSize&w=${enc(keyword)}&format=json"
        val list = fetchJson(client, url, "https://y.qq.com/")
            ?.obj("data")?.obj("song")?.arr("list") ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val mid = o.str("songmid") ?: return@mapNotNull null
            val albumMid = o.str("albummid")
            LxSong(
                source = LxSources.TENCENT,
                songmid = mid,
                name = o.str("songname")?.clean() ?: return@mapNotNull null,
                singer = o.arr("singer")?.joinToString("/") {
                    (it as? JsonObject)?.str("name").orEmpty()
                }.orEmpty(),
                albumName = o.str("albumname").orEmpty(),
                interval = o.long("interval"),
                pic = albumMid?.takeIf { it.isNotBlank() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
                qualitys = listOf("128k"),
            )
        }
    }

    // ---------------------------------------------------------------- 酷我

    /**
     * 酷我搜索。
     *
     * ⚠️ 这个接口（`r.s`）返回的**不是标准 JSON**，而是单引号的 Python 风格字面量：
     * `{'abslist':[{'SONGNAME':'晴天', ...}]}`。lx-music 也是用「单引号换双引号」的土办法解析的。
     * 风险：歌名里若含 `'` 会解析失败 —— 该条结果被跳过，不影响整体。
     */
    private fun searchKuwo(client: OkHttpClient, keyword: String, page: Int, pageSize: Int): List<LxSong> {
        val pn = (page.coerceAtLeast(1) - 1) * pageSize
        val url = "http://search.kuwo.cn/r.s" +
            "?all=${enc(keyword)}&ft=music&itemset=web_2013&client=kt" +
            "&pn=$pn&rn=$pageSize&rformat=json&encoding=utf8&show_copyright_off=1&vipver=1"
        val raw = fetchText(client, url, "http://www.kuwo.cn/") ?: return emptyList()
        val list = runCatching {
            JsonParser.parseString(raw.replace('\'', '"')).asJsonObject.getAsJsonArray("abslist")
        }.onFailure { LxLog.w(it, "LxBuiltinSearch: kuwo pseudo-json parse failed") }
            .getOrNull() ?: return emptyList()

        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val musicRid = o.str("MUSICRID") ?: return@mapNotNull null
            val id = musicRid.removePrefix("MUSIC_")
            if (id.isBlank()) return@mapNotNull null
            LxSong(
                source = LxSources.KUWO,
                songmid = id,
                name = o.str("SONGNAME")?.clean() ?: return@mapNotNull null,
                singer = o.str("ARTIST").orEmpty(),
                albumName = o.str("ALBUM").orEmpty(),
                interval = o.long("DURATION"),
                // 实测：PICPATH 常为空串，有值的是 web_albumpic_short（形如 120/xx/yy/zzz.jpg）
                pic = o.str("web_albumpic_short")?.takeIf { it.isNotBlank() }
                    ?.let { "https://img1.kuwo.cn/star/albumcover/$it" },
                qualitys = listOf("128k"),
            )
        }
    }

    // ---------------------------------------------------------------- 咪咕

    private fun searchMigu(client: OkHttpClient, keyword: String, page: Int, pageSize: Int): List<LxSong> {
        val switch = enc("""{"song":1}""")
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do" +
            "?text=${enc(keyword)}&pageNo=${page.coerceAtLeast(1)}&pageSize=$pageSize&searchSwitch=$switch"
        val list = fetchJson(client, url, "https://music.migu.cn/")
            ?.obj("songResultData")?.arr("result") ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val copyrightId = o.str("copyrightId") ?: return@mapNotNull null
            val pic = o.arr("imgItems")
                ?.mapNotNull { (it as? JsonObject)?.str("img") }
                ?.firstOrNull { it.startsWith("http") }
            LxSong(
                source = LxSources.MIGU,
                // 咪咕的取流与歌词都认 copyrightId
                songmid = copyrightId,
                name = o.str("name")?.clean() ?: return@mapNotNull null,
                singer = o.arr("singers")?.joinToString("/") {
                    (it as? JsonObject)?.str("name").orEmpty()
                }.orEmpty(),
                albumName = o.arr("albums")?.firstOrNull()?.let { (it as? JsonObject)?.str("name") }.orEmpty(),
                interval = o.long("duration") / 1000L,
                pic = pic,
                qualitys = listOf("128k"),
                copyrightId = copyrightId,
                // 歌词地址是搜索结果自带的纯文本 LRC，顺手存进 extra，歌词阶段直接用
                extraFields = o.str("lyricUrl")?.let { mapOf("lyricUrl" to it) } ?: emptyMap(),
            )
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun fetchText(client: OkHttpClient, url: String, referer: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LxLog.w("LxBuiltinSearch: HTTP ${resp.code} for ${url.take(80)}")
                    return null
                }
                resp.body?.string()
            }
        }.onFailure { LxLog.w(it, "LxBuiltinSearch: request failed ${url.take(80)}") }.getOrNull()
    }

    private fun fetchJson(client: OkHttpClient, url: String, referer: String): JsonObject? {
        val text = fetchText(client, url, referer) ?: return null
        return runCatching { JsonParser.parseString(text).asJsonObject }
            .onFailure { LxLog.w(it, "LxBuiltinSearch: json parse failed ${url.take(80)}") }
            .getOrNull()
    }

    // ---------------------------------------------------------------- 小工具

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.long(key: String): Long {
        val p = get(key)?.takeIf { it.isJsonPrimitive } ?: return 0L
        return runCatching { p.asLong }.getOrElse {
            runCatching { p.asString.toDouble().toLong() }.getOrDefault(0L)
        }
    }

    /** 搜索结果常带 `<em>` 高亮标签和 HTML 实体（酷我会给 `&nbsp;`）。 */
    private fun String.clean(): String = replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    /** 网易云会给 http 封面，统一升到 https，避免被明文策略/混合内容挡掉。 */
    private fun String.https(): String = replaceFirst("http://", "https://")
}
