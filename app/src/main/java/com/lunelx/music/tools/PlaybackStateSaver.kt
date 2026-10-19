package com.lunelx.music.tools

import android.content.SharedPreferences
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * 播放状态的持久化快照。
 *
 * 注意：队列与当前歌曲都用 **uid** 而不是 id 标识，
 * 因为在线歌曲没有 MediaStore ID（其 id 恒为 0）。
 * 用 id 会导致所有在线歌曲互相覆盖。
 */
data class SavedPlaybackState(
    val currentUid: String = "",
    val playbackPositionMs: Long = 0L,
    val queueUids: List<String> = emptyList(),
    /**
     * 队列里**在线歌曲**的快照。
     *
     * [queueUids] 只能表达「队列里有哪几首」，而在线歌不是音乐库里的实体，
     * 重启后按 uid 在本地音乐库里反查必然落空 —— 所以纯在线队列重启后会退化成一两首。
     * 这里额外存下在线歌的完整 Song（一般只有几首，不会把 SharedPreferences 撑大）。
     *
     * 可空是刻意的：Gson 反序列化**不会**使用 Kotlin 的默认值，
     * 老版本存下的 JSON 里没有这个字段，会读出 null。
     */
    val queueOnlineSongs: List<Song>? = null,
    val playlistId: Long? = null,
    val playlistName: String? = null,
    val playlistCategory: String? = null,
    val shuffledIndices: List<Int> = emptyList(),
    val shufflePosition: Int = -1,
    val frontQueueInsertCount: Int = 0,
    val wasPlaying: Boolean = false,
    val queueSections: List<QueuedSection> = emptyList(),
    val savedSong: Song? = null,
)

class PlaybackStateSaver(private val prefs: SharedPreferences) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
        .create()

    fun save(state: SavedPlaybackState) {
        prefs.edit().putString(KEY_SAVED_STATE, gson.toJson(state)).commit()
    }

    fun restore(): SavedPlaybackState? {
        val json = prefs.getString(KEY_SAVED_STATE, null) ?: return null
        return try {
            gson.fromJson(json, SavedPlaybackState::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_SAVED_STATE).apply()
    }

    companion object {
        private const val KEY_SAVED_STATE = "saved_playback_state"
    }
}
