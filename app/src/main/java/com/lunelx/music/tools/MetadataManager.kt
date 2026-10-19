package com.lunelx.music.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.lunelx.music.data.MusicDatabase
import com.lunelx.music.data.SongOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用户对歌曲的覆盖信息（改过的标题/封面 + 收藏状态）的存取。
 *
 * **所有入口都按 [Song.uid] 而不是 [Song.id] 索引。**
 * 在线歌曲的 id 恒为 0，用 id 索引会让「所有在线歌共用一条记录」：
 * 收藏一首等于收藏全部、改一次标题串到别的歌上、封面互相覆盖。
 * 本地歌曲的 uid 是 `local:<id>`，与老数据的 MediaStore id 一一对应。
 */
class MetadataManager(private val context: Context) {
    private val database = MusicDatabase.getDatabase(context)
    private val coversDir = File(context.filesDir, "covers")

    suspend fun updateSongMetadata(
        songUid: String,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = database.songOverrideDao().getOverrideForSong(songUid)

            val finalCoverUri = if (coverUri != null) {
                if (!coverUri.startsWith("file://${context.filesDir}")) {
                    saveCustomCover(songUid, coverUri.toUri())?.toString() ?: coverUri
                } else {
                    coverUri
                }
            } else {
                existing?.coverUri
            }

            val override = SongOverride(
                songUid = songUid,
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                coverUri = finalCoverUri,
                isFavorite = existing?.isFavorite ?: false,
                // 编辑信息只对本地歌曲开放；万一将来对在线歌开放，也别把快照丢掉
                meta = existing?.meta
            )
            database.songOverrideDao().insertOverride(override)
            true
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error saving metadata to Room", e)
            false
        }
    }

    /**
     * 切换/设置收藏。
     *
     * 传整首 [Song] 而不是 uid，是因为在线歌曲还需要把**快照**一起存下来：
     * 它不在音乐库里，光记一个 uid 重启后既显示不出来也播不了。
     */
    suspend fun setFavorite(song: Song, isFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val dao = database.songOverrideDao()
            val existing = dao.getOverrideForSong(song.uid)

            val override = if (song.isOnline) {
                // 在线歌：元数据跟着快照一起写死，重启后能独立重建这一行
                SongOverride(
                    songUid = song.uid,
                    title = existing?.title ?: song.title,
                    artist = existing?.artist ?: song.artist,
                    album = existing?.album ?: song.album,
                    genre = existing?.genre ?: song.genre,
                    coverUri = existing?.coverUri ?: song.lxPicUrl ?: song.coverUrl,
                    isFavorite = isFavorite,
                    meta = SongJson.encode(song.copy(isFavorite = isFavorite))
                )
            } else {
                existing?.copy(isFavorite = isFavorite)
                    ?: SongOverride(songUid = song.uid, isFavorite = isFavorite)
            }

            dao.insertOverride(override)
            true
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error updating favorite status", e)
            false
        }
    }

    /**
     * 收藏的在线歌曲，重启后仍可用（靠 `meta` 里的 Song 快照重建）。
     * 默认关闭在线音源时返回空列表 —— 音源被关掉后这些歌本来也播不了。
     */
    suspend fun getFavoriteOnlineSongs(onlineEnabled: Boolean): List<Song> = withContext(Dispatchers.IO) {
        if (!onlineEnabled) return@withContext emptyList()
        try {
            database.songOverrideDao().getFavoriteOnlineOverrides().mapNotNull { override ->
                val snapshot = SongJson.decode(override.meta)
                (snapshot ?: minimalOnlineSong(override))?.copy(isFavorite = true)
            }
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error loading favorite online songs", e)
            emptyList()
        }
    }

    /** 没有快照时的兜底：只有 uid + 标题歌手，能显示但播放可能缺 hash 等字段。 */
    private fun minimalOnlineSong(override: SongOverride): Song? {
        val (source, songmid) = Song.lxOf(override.songUid) ?: return null
        return Song(
            id = 0L,
            albumId = 0L,
            title = override.title ?: songmid,
            artist = override.artist ?: "在线",
            album = override.album ?: "",
            duration = 0L,
            uri = Song.lxUri(source, songmid),
            path = "",
            folderName = "在线",
            coverUrl = override.coverUri,
            isFavorite = true,
            lxSource = source,
            songmid = songmid,
            format = "在线",
        )
    }

    suspend fun clearMetadataOverride(songUid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = database.songOverrideDao().getOverrideForSong(songUid)
            if (existing != null) {
                // 删掉自定义封面（uid 命名 + 老的 id 命名，两种都清）
                coverFileFor(songUid).takeIf { it.exists() }?.delete()
                Song.localIdOf(songUid)?.let { legacyId ->
                    File(coversDir, "cover_$legacyId.jpg").takeIf { it.exists() }?.delete()
                }

                // Keep only the songUid and isFavorite status, clear other metadata
                val clearedOverride = SongOverride(
                    songUid = songUid,
                    isFavorite = existing.isFavorite
                )
                database.songOverrideDao().insertOverride(clearedOverride)
            }
            true
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error clearing metadata override", e)
            false
        }
    }

    /** 自定义封面文件名：`cover_<uid 转义>.jpg`，冒号在部分路径下不安全，统一转下划线。 */
    private fun coverFileFor(songUid: String): File =
        File(coversDir, "cover_${songUid.replace(Regex("[^A-Za-z0-9._-]"), "_")}.jpg")

    private fun saveCustomCover(songUid: String, imageUri: Uri): Uri? {
        return try {
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }
            val coverFile = coverFileFor(songUid)
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                coverFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // 清掉老版本按 MediaStore id 命名的封面，避免残留
            Song.localIdOf(songUid)?.let { legacyId ->
                File(coversDir, "cover_$legacyId.jpg")
                    .takeIf { it.exists() && it.name != coverFile.name }
                    ?.delete()
            }
            Uri.fromFile(coverFile)
        } catch (e: Exception) {
            Log.e("MetadataManager", "Error saving custom cover", e)
            null
        }
    }
}
