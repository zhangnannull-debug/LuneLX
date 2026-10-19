package com.lunelx.music.tools

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * `Song` ⇄ JSON 快照。
 *
 * 为什么需要它：在线歌曲**不是** MediaStore 里的实体。它的信息（标题、歌手、封面、
 * 酷狗需要的 hash、咪咕的 copyrightId……）只存在于当次搜索的内存里，进程一退出就没了。
 * 而「收藏 / 播放统计 / 播放队列」这些持久化结构需要跨进程记住一首在线歌，
 * 光有 uid 只能记住「是哪首」，记不住「长什么样、怎么取流」。
 *
 * 于是把整首 Song 序列化进 Room 的 `meta` 列（[com.lunelx.music.data.SongOverride.meta] 与
 * [com.lunelx.music.data.PlaybackStats.meta]），重启后 [decode] 回来即可直接播放。
 *
 * 用独立的 Gson 实例（带 [UriTypeAdapter]）而不是全局默认实例：`Song.uri` 是 `Uri`，
 * 默认适配器会把它反射成一堆内部字段。
 */
object SongJson {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
        .disableHtmlEscaping()
        .create()

    fun encode(song: Song): String = gson.toJson(song)

    fun decode(json: String?): Song? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, Song::class.java) }.getOrNull()
    }
}
