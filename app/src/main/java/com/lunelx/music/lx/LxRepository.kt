package com.lunelx.music.lx

import android.content.Context
import android.net.Uri
import com.lunelx.music.lx.engine.LxCookieStore
import com.lunelx.music.lx.engine.LxLyricResult
import com.lunelx.music.lx.engine.LxQualities
import com.lunelx.music.lx.engine.LxScriptCapabilities
import com.lunelx.music.lx.engine.LxScriptEngine
import com.lunelx.music.lx.engine.LxScriptMeta
import com.lunelx.music.lx.engine.LxSong
import com.lunelx.music.lx.engine.LxSourceInfo
import com.lunelx.music.lx.engine.LxSources
import com.lunelx.music.lx.engine.LxLog
import com.lunelx.music.lx.net.LxResolvedStream
import com.lunelx.music.lx.net.LxStreamProxy
import com.lunelx.music.lx.net.LxStreamSpec
import com.lunelx.music.lx.search.LxBuiltinLyrics
import com.lunelx.music.lx.search.LxBuiltinSearch
import com.lunelx.music.lx.store.LxScriptStore
import com.lunelx.music.data.MusicDatabase
import com.lunelx.music.lx.discover.LxDiscover
import com.lunelx.music.lx.discover.LxDiscoverBoard
import com.lunelx.music.tools.MetadataManager
import com.lunelx.music.tools.Song
import com.lunelx.music.tools.SongUid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * LX 音源子系统的统一入口。
 *
 * 职责边界：
 * - 管理脚本的加载/卸载（委托 [LxScriptEngine]）
 * - 把脚本返回的 [LxSong] 转成本项目的 [Song]（带 uid）
 * - 为在线歌曲分配流代理地址（委托 [LxStreamProxy]）
 * - 缓存已解析的直链，减少重复请求
 *
 * 单例，通过 [getInstance] 获取。不依赖任何 DI 框架。
 */
class LxRepository private constructor(private val context: Context) {

    private val cookieStore = LxCookieStore(context)

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scriptStore = LxScriptStore(context)

    private val metadata = MetadataManager(context)

    private val engine = LxScriptEngine(context, httpClient, cookieStore)

    /** 已解析直链的缓存：token -> (url, headers, 过期时间)。 */
    private data class CachedStream(
        val url: String,
        val headers: Map<String, String>,
        val expiresAt: Long,
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    private val proxy = LxStreamProxy(
        resolve = { spec -> resolveStreamInternal(spec) },
        invalidate = { token -> streamCache.remove(token) },
    )

    private val loadMutex = Mutex()

    private val _scripts = MutableStateFlow<List<LxScriptMeta>>(emptyList())

    /** 当前已导入的脚本列表，供 UI 观察。 */
    val scripts: StateFlow<List<LxScriptMeta>> = _scripts.asStateFlow()

    private val _capabilities = MutableStateFlow<Map<String, LxScriptCapabilities>>(emptyMap())

    /** 每个脚本上报的能力，key 是脚本 assetPath。 */
    val capabilities: StateFlow<Map<String, LxScriptCapabilities>> = _capabilities.asStateFlow()

    /** 用户偏好的音质，默认 320k，可在设置里改。 */
    @Volatile
    var preferredQuality: String = LxQualities.Q320

    /**
     * 是否启用在线音源。关闭后 UI 隐藏在线入口。
     *
     * 必须**持久化**：这里原来是一个普通内存变量，进程一重启就静默变回 false，
     * 用户明明开过、导入过脚本，重新打开却看到「还没有可用的音源脚本」。
     */
    var onlineEnabled: Boolean
        get() = scriptStore.isOnlineEnabled()
        set(value) = scriptStore.setOnlineEnabled(value)

    // ------------------------------------------------------------ 脚本管理

    /** 从磁盘重新读取脚本列表并加载全部已启用的脚本。 */
    suspend fun refreshScripts() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            val metas = scriptStore.listScripts()
            _scripts.value = metas
            val caps = mutableMapOf<String, LxScriptCapabilities>()
            for (meta in metas) {
                if (!scriptStore.isEnabled(meta.assetPath)) continue
                if (engine.isLoaded(meta.assetPath)) {
                    engine.getCapabilities(meta.assetPath)?.let { caps[meta.assetPath] = it }
                    continue
                }
                val result = engine.loadScript(meta)
                result.onSuccess { caps[meta.assetPath] = it }
                    .onFailure { LxLog.e(it, "LxRepository: load script failed ${meta.name}") }
            }
            _capabilities.value = caps
            LxLog.i("LxRepository: ${metas.size} scripts, ${caps.size} loaded")
        }
    }

    /**
     * 导入一个脚本。
     *
     * @param fileName 目标文件名（建议用脚本名+.js）
     * @param content 脚本文本
     * @return 加载成功时返回能力信息
     */
    suspend fun importScript(fileName: String, content: String): Result<LxScriptCapabilities> =
        withContext(Dispatchers.IO) {
            val meta = scriptStore.importScript(fileName, content)
                ?: return@withContext Result.failure(IllegalArgumentException("脚本保存失败"))
            scriptStore.setEnabled(meta.assetPath, true)
            val result = engine.loadScript(meta)
            result.onSuccess {
                _capabilities.value = _capabilities.value + (meta.assetPath to it)
                _scripts.value = scriptStore.listScripts()
            }
            result
        }

    /** 删除脚本。 */
    fun removeScript(assetPath: String) {
        engine.unloadScript(assetPath)
        scriptStore.deleteScript(assetPath)
        _scripts.value = scriptStore.listScripts()
        _capabilities.value = _capabilities.value - assetPath
    }

    /** 启用/停用脚本。 */
    suspend fun setScriptEnabled(assetPath: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        scriptStore.setEnabled(assetPath, enabled)
        if (!enabled) {
            engine.unloadScript(assetPath)
            _capabilities.value = _capabilities.value - assetPath
        } else {
            val meta = scriptStore.listScripts().firstOrNull { it.assetPath == assetPath }
            if (meta != null) {
                engine.loadScript(meta).onSuccess {
                    _capabilities.value = _capabilities.value + (assetPath to it)
                }
            }
        }
        _scripts.value = scriptStore.listScripts()
    }

    /** 某个音源是否有脚本支持。 */
    fun isSourceAvailable(source: String): Boolean {
        if (!onlineEnabled) return false
        return _capabilities.value.values.any { caps ->
            caps.sources.containsKey(source) && caps.sources[source]?.type != "none"
        }
    }

    /** 当前可用的音源列表（有脚本声明的才算）。 */
    fun availableSources(): List<String> {
        if (!onlineEnabled) return emptyList()
        val declared = _capabilities.value.values
            .flatMap { it.sources.entries }
            .filter { it.value.type != "none" }
            .map { it.key }
            .toSet()
        return LxSources.ALL.filter { it in declared }
    }

    /**
     * 某音源当前是否能**搜索**。
     *
     * 两种来源：
     * 1. **内置搜索**（[LxBuiltinSearch]）——客户端自己调平台接口，不依赖脚本；
     * 2. 脚本自己声明了 `search` action（少见）。
     *
     * 之所以要区分：lx 音源脚本（含「裤佬」）根本不实现 search，在 lx-music 里
     * 搜索是客户端做的。只按脚本判断的话，这类脚本永远搜不了。
     */
    fun hasSearchableSource(source: String): Boolean {
        if (!onlineEnabled) return false
        if (LxBuiltinSearch.isSupported(source)) return true
        return _capabilities.value.entries.any { (assetPath, caps) ->
            caps.sources.containsKey(source) && engine.supportsAction(assetPath, source, "search")
        }
    }

    /** 当前所有可搜索的音源。 */
    fun searchableSources(): List<String> =
        availableSources().filter { hasSearchableSource(it) }

    /** 某音源可用的音质列表。 */
    fun qualitiesFor(source: String): List<String> {
        val all = _capabilities.value.values
            .mapNotNull { it.sources[source] }
            .flatMap { it.qualitys }
            .distinct()
        return if (all.isEmpty()) LxQualities.DESCENDING else
            all.sortedByDescending { LxQualities.rank(it) }
    }

    // ------------------------------------------------------------ 搜索

    /**
     * 跨所有已启用脚本搜索。
     * 多个脚本支持同一音源时取第一个成功的，避免重复结果。
     */
    suspend fun search(source: String, keyword: String, page: Int = 1): List<Song> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()

            // 1) 优先走内置搜索：脚本没有 search 能力，这是绝大多数音源的唯一可用路径
            if (LxBuiltinSearch.isSupported(source)) {
                val builtin = runCatching {
                    LxBuiltinSearch.search(httpClient, source, keyword, page)
                }.onFailure { LxLog.w(it, "LxRepository: builtin search failed for $source") }
                    .getOrNull()
                if (!builtin.isNullOrEmpty()) {
                    LxLog.i("LxRepository: builtin search $source '$keyword' -> ${builtin.size} results")
                    return@withContext applyOverrides(builtin.map { it.toSong(defaultSource = source) })
                }
                LxLog.w("LxRepository: builtin search empty for $source, falling back to scripts")
            }

            // 2) 退回「自带 search 的第三方脚本」
            val candidates = _capabilities.value.entries
                .filter { it.value.sources.containsKey(source) }
                .map { it.key }
            if (candidates.isEmpty()) {
                LxLog.w("LxRepository: no script for source $source")
                return@withContext emptyList()
            }
            // 只挑真正声明了 search 的脚本，避免白等超时
            val searchable = candidates.filter { engine.supportsAction(it, source, "search") }
            if (searchable.isEmpty()) {
                LxLog.w("LxRepository: source $source has no search-capable script")
                return@withContext emptyList()
            }
            for (assetPath in searchable) {
                val raw = runCatching { engine.requestSearch(assetPath, source, keyword, page) }
                    .onFailure { LxLog.w(it, "LxRepository: search failed on $assetPath") }
                    .getOrNull()
                if (!raw.isNullOrEmpty()) {
                    // 搜索是按音源发起的，脚本没逐条写 source 时用这次请求的 source 兜底
                    return@withContext applyOverrides(raw.map { it.toSong(defaultSource = source) })
                }
            }
            emptyList()
        }

    // ------------------------------------------------------------ 收藏

    /**
     * 把 Room 里的收藏状态（以及用户改过的标题等）回填到刚搜出来的在线歌上。
     *
     * 在线歌每次搜索都是**新建**的 Song 对象，isFavorite 默认 false —— 不主动回填的话，
     * 用户上次收藏的歌这次看起来是没收藏的（点一下反而变成"取消收藏"）。
     */
    private suspend fun applyOverrides(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        return runCatching {
            val dao = MusicDatabase.getDatabase(context).songOverrideDao()
            val overrides = dao.getOverridesForSongs(songs.map { it.uid }).associateBy { it.songUid }
            if (overrides.isEmpty()) return songs
            songs.map { song ->
                val o = overrides[song.uid] ?: return@map song
                song.copy(
                    title = o.title?.takeIf { it.isNotBlank() } ?: song.title,
                    artist = o.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                    album = o.album?.takeIf { it.isNotBlank() } ?: song.album,
                    coverUrl = o.coverUri ?: song.coverUrl,
                    isFavorite = o.isFavorite,
                )
            }
        }.getOrElse {
            LxLog.w(it, "LxRepository: apply favorite overrides failed")
            songs
        }
    }

    /** 收藏的在线歌曲（重启后仍可用，靠 Room 里的 Song 快照重建）。 */
    suspend fun favoriteOnlineSongs(): List<Song> = withContext(Dispatchers.IO) {
        metadata.getFavoriteOnlineSongs(onlineEnabled)
            .filter { SongUid.isOnlineUid(it.uid) }
    }

    // ------------------------------------------------------------ 发现（平台榜单）

    /** 该平台有没有内置的「发现」数据（目前是官方榜单）。 */
    fun hasDiscover(source: String): Boolean = onlineEnabled && LxDiscover.isSupported(source)

    /** 取某平台的榜单列表。 */
    suspend fun discoverBoards(source: String): List<LxDiscoverBoard> = withContext(Dispatchers.IO) {
        if (!onlineEnabled) return@withContext emptyList()
        runCatching { LxDiscover.boards(httpClient, source) }
            .onFailure { LxLog.w(it, "LxRepository: discover boards failed for $source") }
            .getOrDefault(emptyList())
    }

    /**
     * 取某个榜单里的歌，转成 [Song]（带 uid、封面、收藏状态）。
     *
     * 走的是与搜索完全相同的转换与回填路径，所以「发现」里的歌和搜出来的歌行为一致：
     * 能点播、能下载、能收藏、队列里能定位。
     */
    suspend fun discoverBoardSongs(
        source: String,
        boardId: String,
        limit: Int = 60,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (!onlineEnabled) return@withContext emptyList()
        val raw = runCatching { LxDiscover.boardSongs(httpClient, source, boardId, limit) }
            .onFailure { LxLog.w(it, "LxRepository: discover songs failed for $source/$boardId") }
            .getOrDefault(emptyList())
        if (raw.isEmpty()) return@withContext emptyList()
        applyOverrides(raw.map { it.toSong(defaultSource = source) })
    }

    // ------------------------------------------------------------ 按 ID 直接取流

    /**
     * 用音源内部的歌曲 ID 直接构造一首可播放的在线歌曲。
     *
     * 存在的意义：不少音源脚本（例如「裤佬音源」）**只实现了 musicUrl**，
     * 没有 search。这类脚本无法通过关键词搜索使用，但只要能拿到 ID
     * 就能播放。用户可以从别处（网页、分享链接、其他客户端）取得 ID，
     * 在这里粘贴即可。
     *
     * @param source 音源标识（wy/tx/kg/kw/mg）
     * @param songmid 音源内歌曲 ID
     * @param title 显示用标题，留空则用 ID 代替
     * @param hash 酷狗等音源必需的文件 hash
     */
    fun buildSongById(
        source: String,
        songmid: String,
        title: String = "",
        hash: String? = null,
    ): Song? {
        if (!onlineEnabled) return null
        if (!isSourceAvailable(source)) return null
        if (songmid.isBlank()) return null
        val label = title.ifBlank { "$source:$songmid" }
        return Song(
            id = 0L,
            albumId = 0L,
            title = label,
            artist = "在线",
            album = "",
            duration = 0L,
            uri = Song.lxUri(source, songmid),
            path = "",
            folderName = "在线",
            lxSource = source,
            songmid = songmid,
            lxHash = hash,
            format = "在线",
        )
    }

    // ------------------------------------------------------------ 播放地址

    /**
     * 把在线歌曲登记到流代理，返回可交给 ExoPlayer 的本地地址。
     * 同一首歌同音质会复用同一个 token，从而复用 ExoPlayer 缓存。
     */
    fun streamUrlFor(song: Song, quality: String = preferredQuality): String? {
        if (!song.isOnline) return null
        val source = song.lxSource ?: return null
        val songmid = song.songmid ?: return null
        val spec = LxStreamSpec(
            token = tokenOf(song.uid, quality),
            uid = song.uid,
            source = source,
            songmid = songmid,
            quality = quality,
            extra = buildExtra(song),
        )
        return proxy.register(spec)
    }

    /** 播放结束或切歌时释放代理条目。 */
    fun releaseStream(song: Song, quality: String = preferredQuality) {
        if (!song.isOnline) return
        val token = tokenOf(song.uid, quality)
        proxy.unregister(token)
        streamCache.remove(token)
    }

    /**
     * 实际解析逻辑，由流代理在需要时调用。
     * 命中缓存且未过期就直接返回，否则重新问脚本。
     */
    private suspend fun resolveStreamInternal(spec: LxStreamSpec): LxResolvedStream? {
        streamCache[spec.token]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) {
                return LxResolvedStream(cached.url, cached.headers)
            }
            streamCache.remove(spec.token)
        }

        val assetPath = findScriptFor(spec.source)
        if (assetPath == null) {
            LxLog.w("LxRepository: no script for ${spec.source}")
            return null
        }

        // 从期望音质开始，逐级降档重试
        val tryOrder = qualityFallback(spec.source, spec.quality)
        for (q in tryOrder) {
            val url = runCatching {
                engine.requestMusicUrl(assetPath, spec.source, spec.songmid, q, spec.extra)
            }.onFailure { LxLog.w(it, "LxRepository: musicUrl failed q=$q") }.getOrNull()

            if (!url.isNullOrBlank()) {
                val headers = buildHeaders(spec.source)
                streamCache[spec.token] = CachedStream(
                    url = url,
                    headers = headers,
                    expiresAt = System.currentTimeMillis() + URL_TTL_MS,
                )
                LxLog.i("LxRepository: resolved ${spec.source}/${spec.songmid} q=$q")
                return LxResolvedStream(url, headers)
            }
        }
        LxLog.w("LxRepository: all qualities failed for ${spec.source}/${spec.songmid}")
        return null
    }

    /** 音质降档顺序：从期望音质开始，往下退。 */
    private fun qualityFallback(source: String, wanted: String): List<String> {
        val available = qualitiesFor(source)
        val wantedRank = LxQualities.rank(wanted)
        val lower = available.filter { LxQualities.rank(it) >= wantedRank }
        return (listOf(wanted) + lower).distinct()
    }

    private fun findScriptFor(source: String): String? =
        _capabilities.value.entries
            .firstOrNull { it.value.sources.containsKey(source) }
            ?.key

    /** 构造转发请求头。Referer 是多数平台防盗链的关键。 */
    private fun buildHeaders(source: String): Map<String, String> {
        val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
        )
        cookieStore.get(source)?.let { headers["Cookie"] = it }
        when (source) {
            LxSources.KUGOU -> headers["Referer"] = "https://www.kugou.com/"
            LxSources.KUWO -> headers["Referer"] = "https://www.kuwo.cn/"
            LxSources.TENCENT -> headers["Referer"] = "https://y.qq.com/"
            LxSources.NETEASE -> headers["Referer"] = "https://music.163.com/"
            LxSources.MIGU -> headers["Referer"] = "https://music.migu.cn/"
        }
        return headers
    }

    /**
     * 把脚本需要的额外字段拼成请求参数。
     *
     * 注意 `hash` 与 `copyrightId` 是 lx 脚本的一等字段，但 `parseSearchResult`
     * 把它们从 `extraFields` 里排除了（因为它们是标准字段而非自定义字段）。
     * 因此这里必须**单独补回去**，否则酷狗（依赖 hash）等音源取不到播放地址。
     */
    private fun buildExtra(song: Song): Map<String, String> {
        val extra = song.lxExtra.toMutableMap()
        // 标准字段回填
        extra["name"] = song.title
        extra["singer"] = song.artist
        extra["albumName"] = song.album
        extra["interval"] = (song.duration / 1000L).toString()
        song.lxHash?.let { extra["hash"] = it }
        song.lxCopyrightId?.let { extra["copyrightId"] = it }
        song.lxPicUrl?.let { extra["pic"] = it }
        return extra
    }

    private fun tokenOf(uid: String, quality: String): String =
        uid.replace(':', '_') + "_" + quality

    // ------------------------------------------------------------ 封面 / 歌词

    /** 取在线歌曲封面地址。结果回填到 [Song.lxPicUrl]。 */
    suspend fun fetchCoverUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (!song.isOnline) return@withContext null
        song.lxPicUrl?.let { return@withContext it }
        val source = song.lxSource ?: return@withContext null
        val songmid = song.songmid ?: return@withContext null
        val assetPath = findScriptFor(source) ?: return@withContext null
        runCatching {
            engine.requestPic(assetPath, source, songmid, buildExtra(song))
        }.getOrNull()
    }

    /** 取在线歌曲歌词。 */
    suspend fun fetchLyric(song: Song): LxLyricResult? = withContext(Dispatchers.IO) {
        if (!song.isOnline) return@withContext null
        val source = song.lxSource ?: return@withContext null
        val songmid = song.songmid ?: return@withContext null

        // 1) 内置歌词优先：脚本一般没有 lyric 能力（裤佬脚本里 lyric 出现 0 次）
        if (LxBuiltinLyrics.isSupported(source)) {
            val builtin = runCatching {
                LxBuiltinLyrics.lyric(httpClient, source, songmid, buildExtra(song))
            }.onFailure { LxLog.w(it, "LxRepository: builtin lyric failed for $source") }
                .getOrNull()
            if (builtin != null) {
                LxLog.d("LxRepository: builtin lyric $source/${songmid} ok")
                return@withContext builtin
            }
            LxLog.w("LxRepository: builtin lyric empty for $source, falling back to scripts")
        }

        // 2) 退回脚本自己的 lyric action
        val assetPath = findScriptFor(source) ?: return@withContext null
        runCatching {
            engine.requestLyric(assetPath, source, songmid, buildExtra(song))
        }.getOrNull()
    }

    // ------------------------------------------------------------ 转换

    /**
     * 把脚本返回的 [LxSong] 转成本项目的 [Song]。
     *
     * @param defaultSource 脚本没有逐条返回 `source` 时用的兜底音源。搜索请求本身就指定了
     *   音源，很多脚本因此不会在每条结果里重复写 `source`；缺省会得到 `source=""`，
     *   于是 `uid` 变成 `lx::<songmid>`（解析不出音源）、`findScriptFor("")` 找不到脚本，
     *   点播放必然失败，日志里只有一句 `LxRepository: no script for `（音源是空的，
     *   甚至看不出是哪首歌）。真机上就是这样踩到的。
     */
    fun LxSong.toSong(defaultSource: String = ""): Song {
        val src = source.ifBlank { defaultSource }
        return Song(
            id = 0L,
            albumId = 0L,
            title = name,
        artist = singer.ifBlank { "未知艺术家" },
        album = albumName,
        // ⚠️ 单位换算：LxSong.interval 是**秒**（parseIntervalSeconds 把 "03:45" 解析成 225），
        // 而 Song.duration 全程按**毫秒**使用（formatDuration 除以 1000、PlaybackManager 用
        // duration * 0.75 判跳播、buildExtra 用 duration / 1000 回填脚本的 interval）。
        // 这里曾经直接写 duration = interval，导致每首在线歌只有 225 毫秒：进度条约等于 0、
        // 搜索列表显示 0:00、而且一开播就被判成"播完 75%"记成跳播、时长统计记 0。
        duration = interval * 1000L,
        uri = Song.lxUri(src, songmid),
        path = "",
        folderName = "在线",
        coverUrl = pic,
        lxSource = src.takeIf { it.isNotBlank() },
        songmid = songmid,
        lxQualities = qualitys,
        lxExtra = extraFields,
        lxPicUrl = pic,
        lxHash = hash,
        lxCopyrightId = copyrightId,
        format = "在线",
    )
    }

    // ------------------------------------------------------------ 生命周期

    fun shutdown() {
        proxy.stop()
        streamCache.clear()
    }

    companion object {
        /** 解析出的直链缓存时长。多数平台直链有效期 30 分钟以上，取保守值。 */
        private const val URL_TTL_MS = 10 * 60 * 1000L

        @Volatile
        private var INSTANCE: LxRepository? = null

        fun getInstance(context: Context): LxRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LxRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
