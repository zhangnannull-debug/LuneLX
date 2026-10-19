package com.lunelx.music.lx.engine

/**
 * LX 音源相关的数据模型。
 *
 * 与 OLX 版本相比做了大幅精简：只保留"跑脚本 + 搜索 + 取播放地址 + 取歌词/封面"
 * 这条主链路需要的东西，去掉了歌单、歌手、专辑、排行榜、账号等官方 API 专属的模型。
 * 那些能力由音源脚本自己实现，脚本返回什么我们解析什么。
 */

/** 支持的音源标识。与 lx-music-mobile 的 source 字符串保持一致。 */
object LxSources {
    const val NETEASE = "wy"
    const val TENCENT = "tx"
    const val KUGOU = "kg"
    const val KUWO = "kw"
    const val MIGU = "mg"

    /**
     * UI 默认展示顺序。
     *
     * 这里是「会被展示/可构造」的音源全集：`LxRepository.availableSources()` 与
     * `isSourceAvailable()` 都以它为白名单，不在表内的音源即使脚本声明了也不会出现。
     * 曾漏掉 MIGU，导致咪咕脚本声明了也没有入口。
     */
    val ALL = listOf(KUGOU, TENCENT, NETEASE, KUWO, MIGU)

    fun displayName(source: String): String = when (source) {
        NETEASE -> "网易云"
        TENCENT -> "QQ音乐"
        KUGOU -> "酷狗"
        KUWO -> "酷我"
        MIGU -> "咪咕"
        else -> source.uppercase()
    }
}

/** 音质标识。与 lx 脚本的 qualitys 字段保持一致。 */
object LxQualities {
    const val MASTER = "master"
    const val ATMOS_PLUS = "atmos+"
    const val ATMOS = "atmos"
    const val FLAC24 = "flac24bit"
    const val FLAC = "flac"
    const val Q320 = "320k"
    const val Q128 = "128k"

    /** 从高到低的优先级，用于自动降档。 */
    val DESCENDING = listOf(MASTER, ATMOS_PLUS, ATMOS, FLAC24, FLAC, Q320, Q128)

    fun displayName(quality: String): String = when (quality) {
        MASTER -> "母带"
        ATMOS_PLUS -> "杜比全景声+"
        ATMOS -> "杜比全景声"
        FLAC24 -> "24bit FLAC"
        FLAC -> "FLAC"
        Q320 -> "320kbps"
        Q128 -> "128kbps"
        else -> quality
    }

    fun rank(quality: String): Int = DESCENDING.indexOf(quality).let {
        if (it < 0) DESCENDING.size else it
    }
}

/** 脚本内的一首歌曲。脚本返回的原始结构。 */
data class LxSong(
    val source: String,
    val songmid: String,
    val name: String,
    val singer: String,
    val albumName: String = "",
    val interval: Long = 0L,
    val pic: String? = null,
    val lrc: String? = null,
    val qualitys: List<String> = emptyList(),
    val hash: String? = null,
    val copyrightId: String? = null,
    /** 脚本里除标准字段外的其它字段，原样透传给 musicUrl 请求。 */
    val extraFields: Map<String, String> = emptyMap(),
)

/** 歌词返回。tlyric 为翻译，rlyric 为罗马音，lxlyric 为逐字歌词。 */
data class LxLyricResult(
    val lyric: String,
    val tlyric: String? = null,
    val rlyric: String? = null,
    val lxlyric: String? = null,
)

/** 脚本元信息。 */
data class LxScriptMeta(
    val assetPath: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val isImported: Boolean = false,
)

/** 脚本声明支持的单个音源。 */
data class LxSourceInfo(
    val source: String,
    val type: String = "music",
    val actions: List<String> = emptyList(),
    val qualitys: List<String> = emptyList(),
)

/** 脚本在 lx_setup 里上报的能力。 */
data class LxScriptCapabilities(
    val sources: Map<String, LxSourceInfo> = emptyMap(),
)

/** 脚本更新提示（脚本内部调用 lx.updateAlert 时触发）。 */
data class LxScriptUpdateAlert(
    val scriptName: String,
    val version: String,
    val updateUrl: String?,
    val log: String,
    val assetPath: String,
    val isImported: Boolean,
)
