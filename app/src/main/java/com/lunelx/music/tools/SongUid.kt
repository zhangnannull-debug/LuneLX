package com.lunelx.music.tools

/**
 * 歌曲唯一标识（uid）的构造与解析。
 *
 * 独立成对象的原因：`Song` 依赖 `android.net.Uri`，在纯 JVM 单元测试里无法实例化；
 * 而 uid 的规则是整个混合队列的基石，必须能被单独测试。
 * 这里刻意不引用任何 Android 类。
 *
 * 格式：
 * - 本地歌曲 `local:<MediaStoreId>`
 * - 在线歌曲 `lx:<source>:<songmid>`
 */
object SongUid {

    private const val PREFIX_LOCAL = "local:"
    private const val PREFIX_LX = "lx:"
    private const val PREFIX_SONG_STATS = "SONG_"

    /**
     * 构造 uid。
     *
     * @param localId 本地歌曲的 MediaStore ID；在线歌曲传任意值（通常 0）
     * @param source 在线音源标识，本地歌曲传 null
     * @param songmid 音源内歌曲 ID，本地歌曲传 null
     */
    fun of(localId: Long, source: String?, songmid: String?): String =
        if (source != null && songmid != null) {
            "$PREFIX_LX$source:$songmid"
        } else {
            "$PREFIX_LOCAL$localId"
        }

    /** 从 uid 反解本地 MediaStore ID；非本地或格式错误返回 null。 */
    fun localIdOf(uid: String): Long? =
        uid.takeIf { it.startsWith(PREFIX_LOCAL) }
            ?.removePrefix(PREFIX_LOCAL)
            ?.toLongOrNull()

    /**
     * 从 uid 反解 (source, songmid)；非在线或格式错误返回 null。
     *
     * 注意只按**第一个**冒号切分，因为 songmid 本身可能含冒号
     * （例如 QQ 音乐的某些 ID、酷狗的自定义 hash）。
     */
    fun lxOf(uid: String): Pair<String, String>? {
        if (!uid.startsWith(PREFIX_LX)) return null
        val rest = uid.removePrefix(PREFIX_LX)
        val idx = rest.indexOf(':')
        if (idx <= 0 || idx >= rest.length - 1) return null
        return rest.substring(0, idx) to rest.substring(idx + 1)
    }

    /** 是否为在线歌曲的 uid。 */
    fun isOnlineUid(uid: String): Boolean = uid.startsWith(PREFIX_LX)

    /** 是否为本地歌曲的 uid。 */
    fun isLocalUid(uid: String): Boolean = uid.startsWith(PREFIX_LOCAL)

    /**
     * 播放统计行的主键：`SONG_<uid>`。
     *
     * 与 [uidFromStatsId] 成对使用 —— 写和读必须用同一套格式，
     * 否则统计页会静默地一首歌都查不出来（以前就出现过：行里存的是 `SONG_<id>`，
     * 而在线歌 id 恒为 0，所有在线歌堆进同一行）。
     */
    fun statsIdOf(uid: String): String = "$PREFIX_SONG_STATS$uid"

    /** 从统计行主键反解 uid；不是歌曲统计行时返回 null。 */
    fun uidFromStatsId(statsId: String): String? =
        statsId.removePrefix(PREFIX_SONG_STATS)
            .takeIf { it.length != statsId.length && it.isNotBlank() }

    /**
     * 老版本直接拿 MediaStore id（纯数字）当持久化 key（AI 偏好、播放统计都是）。
     * 把它翻译成 uid；不是老格式（比如已经是 `local:1`）时返回 null。
     */
    fun fromLegacyIdKey(key: String): String? = key.toLongOrNull()?.let { of(it, null, null) }
}
