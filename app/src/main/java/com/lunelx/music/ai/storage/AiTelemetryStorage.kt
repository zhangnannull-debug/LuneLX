package com.lunelx.music.ai.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lunelx.music.ai.model.SongInteraction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 偏好的持久化。
 *
 * key 是歌曲 **uid**（`local:123` / `lx:wy:0039...`），不是 MediaStore id ——
 * 在线歌曲 id 恒为 0，按 id 存会把所有在线歌的偏好合并成同一首。
 *
 * 老版本按 id（纯数字字符串）存盘，[loadFromDisk] 会把它们迁移成 `local:<id>`，
 * 用户的历史偏好不丢。
 */
class AiTelemetryStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lune_ai_telemetry", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()
    private val storageScope = CoroutineScope(Dispatchers.IO + Job())
    private val memoryCache = ConcurrentHashMap<String, SongInteraction>()
    private var saveJob: Job? = null

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            val json = prefs.getString("interactions_map", null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<Map<String, SongInteraction>>() {}.type
                val loaded: Map<String, SongInteraction>? = gson.fromJson(json, type)
                if (loaded != null) {
                    for ((k, v) in loaded) {
                        val uid = legacyKeyToUid(k)
                        if (uid.isNotBlank()) {
                            if (v.nextSongTransitions == null) {
                                v.nextSongTransitions = mutableMapOf()
                            }
                            memoryCache[uid] = v
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AiTelemetryStorage", "Error loading telemetry, resetting storage: ${e.message}", e)
            try {
                prefs.edit().remove("interactions_map").apply()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    /** 老数据：key 是纯数字的 MediaStore id → 迁移成 `local:<id>`。 */
    private fun legacyKeyToUid(key: String): String {
        if (key.isNotBlank() && !key.all { it.isDigit() }) return key
        return com.lunelx.music.tools.SongUid.fromLegacyIdKey(key) ?: ""
    }

    fun getInteraction(songUid: String): SongInteraction {
        return memoryCache.computeIfAbsent(songUid) { SongInteraction(songUid = it) }.also {
            if (it.nextSongTransitions == null) {
                it.nextSongTransitions = mutableMapOf()
            }
        }
    }

    fun getAllInteractions(): Map<String, SongInteraction> {
        return memoryCache
    }

    fun recordInteraction(songUid: String, block: (SongInteraction) -> Unit) {
        val interaction = getInteraction(songUid)
        synchronized(interaction) {
            if (interaction.nextSongTransitions == null) {
                interaction.nextSongTransitions = mutableMapOf()
            }
            block(interaction)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = storageScope.launch {
            delay(1500L) // Debounce writes
            try {
                val snapshot = HashMap<String, SongInteraction>(memoryCache.size)
                for ((uid, interaction) in memoryCache) {
                    snapshot[uid] = interaction
                }
                val json = gson.toJson(snapshot)
                prefs.edit().putString("interactions_map", json).apply()
            } catch (e: Exception) {
                Log.e("AiTelemetryStorage", "Error saving telemetry: ${e.message}", e)
            }
        }
    }

    fun clearAll() {
        memoryCache.clear()
        prefs.edit().clear().apply()
    }
}
