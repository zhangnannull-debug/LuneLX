package com.lunelx.music.tools

import android.net.Uri

/**
 * 播放器内部的统一歌曲模型。
 *
 * 关键约定：
 * - [id] 只对本地歌曲有意义（MediaStore ID）。在线歌曲固定为 0。
 * - [uid] 是全局唯一标识，本地为 `local:<id>`，在线为 `lx:<source>:<songmid>`。
 *   **任何跨模块的歌曲查找都必须用 uid，不能用 id。**
 * - [uri] 对在线歌曲是一个占位 Uri（`lx://source/songmid`），真正的播放地址
 *   由 LxStreamProxy 在播放时解析，不会出现在这里。
 */
data class Song(
    val id: Long,
    val albumId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
    val path: String,
    val dateAdded: Long = 0,
    val albumArtUri: Uri? = null,
    val genre: String? = null,
    val folderName: String,
    val isHiFi: Boolean = false,
    val coverUrl: String? = null,
    val isFavorite: Boolean = false,
    val lyrics: String? = null,
    val format: String = "",
    val bitrate: Int? = null,
    val trackNumber: Int = 0,
    val isHiRes: Boolean = false,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,

    // ---- LX 在线音源相关（本地歌曲保持默认值） ----
    /** 音源标识，如 wy / tx / kg / kw / mg。本地歌曲为 null。 */
    val lxSource: String? = null,
    /** 音源内的歌曲 ID。本地歌曲为 null。 */
    val songmid: String? = null,
    /** 该歌曲可用的音质列表，由音源脚本声明。 */
    val lxQualities: List<String> = emptyList(),
    /** 部分音源需要的额外字段（hash / copyrightId 等）。 */
    val lxExtra: Map<String, String> = emptyMap(),
    /** 当前已解析出的封面地址（在线歌曲首次取图后回填）。 */
    val lxPicUrl: String? = null,
    /** 当前已解析出的歌词原文。 */
    val lxLyric: String? = null,
    /** 酷狗等音源必需的文件 hash，作为 musicUrl 的入参。 */
    val lxHash: String? = null,
    /** 部分音源（如咪咕）需要的版权 ID。 */
    val lxCopyrightId: String? = null,
) {
    /** 是否为在线音源歌曲。 */
    val isOnline: Boolean get() = lxSource != null && songmid != null

    /**
     * 全局唯一标识。所有跨模块查找请使用它。
     * 本地：`local:12345`；在线：`lx:wy:0039MnYb0qxYhV`
     */
    val uid: String
        get() = SongUid.of(id, lxSource, songmid)

    /** 是否拥有可直接播放的本地数据源。 */
    val hasLocalSource: Boolean get() = !isOnline && path.isNotBlank()

    companion object {
        private const val PREFIX_LOCAL = "local:"
        private const val PREFIX_LX = "lx:"

        /** 从 uid 反解出本地歌曲的 MediaStore ID；非本地返回 null。 */
        fun localIdOf(uid: String): Long? = SongUid.localIdOf(uid)

        /** 从 uid 反解出在线歌曲的 (source, songmid)；非在线返回 null。 */
        fun lxOf(uid: String): Pair<String, String>? = SongUid.lxOf(uid)

        /** 构造在线歌曲占位 Uri。 */
        fun lxUri(source: String, songmid: String): Uri =
            Uri.parse("lx://$source/$songmid")
    }
}

data class SongArtwork(
    val id: Long,
    val coverUrl: String?,
    val uri: Uri
)

fun Song.toArtwork(): SongArtwork = SongArtwork(id, coverUrl, uri)

/**
 * 在线歌曲的封面地址解析：优先用已解析的 lxPicUrl，
 * 其次退回 coverUrl，最后用本地专辑封面。
 */
fun Song.bestArtworkUri(): Uri? =
    lxPicUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        ?: coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        ?: albumArtUri
