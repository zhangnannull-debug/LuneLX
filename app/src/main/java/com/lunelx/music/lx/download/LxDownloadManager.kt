package com.lunelx.music.lx.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lunelx.music.lx.LxRepository
import com.lunelx.music.lx.engine.LxLog
import com.lunelx.music.tools.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 在线歌曲下载。
 *
 * ## 为什么复用流代理
 * 直链往往需要 Referer/Cookie、还会 301→https→302 跳几次才能拿到音频。
 * [LxRepository.streamUrlFor] 返回的本地代理地址已经把「解析直链 + 带 header + 跟随重定向」
 * 全部封装好了，所以这里直接 GET 那个 `http://127.0.0.1:<port>/stream/<token>` 即可。
 *
 * ## 流程
 * 1. 下载到 `cacheDir` 的临时文件（扩展名按响应 Content-Type 决定）；
 * 2. **注入元数据**：标题/歌手/专辑 + 封面（APIC）+ 歌词（USLT）——这样即使脱离本 App，
 *    其他播放器也能看到封面和歌词；
 * 3. 写入媒体库（Android 10+ MediaStore `Music/Lune/`，`IS_PENDING` 保证写完才可见）；
 * 4. 删除临时文件。
 */
object LxDownloadManager {

    /** 正在下载的歌曲 uid，用于 UI 显示进度并防止重复点击。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isDownloading(uid: String): Boolean = uid in inFlight

    suspend fun download(context: Context, song: Song): Result<String> = withContext(Dispatchers.IO) {
        if (!song.isOnline) {
            return@withContext Result.failure(IllegalArgumentException("不是在线歌曲"))
        }
        if (!inFlight.add(song.uid)) {
            return@withContext Result.failure(IllegalStateException("这首歌正在下载中"))
        }

        var temp: File? = null
        try {
            val repo = LxRepository.getInstance(context)
            val url = repo.streamUrlFor(song)
                ?: return@withContext Result.failure(IOException("取不到播放地址"))

            LxLog.i("LxDownload: start ${song.title} <- ${url.take(90)}")
            val request = Request.Builder().url(url).header("User-Agent", UA).get().build()

            var mime = "audio/mpeg"
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                mime = resp.header("Content-Type")
                    ?.substringBefore(';')?.trim()
                    ?.takeIf { it.startsWith("audio/") }
                    ?: "audio/mpeg"

                val file = File.createTempFile("lxdl_", "." + extensionOf(mime), context.cacheDir)
                temp = file
                file.outputStream().use { out -> resp.body.byteStream().use { it.copyTo(out) } }
            }

            val file = temp ?: return@withContext Result.failure(IOException("下载中断"))

            // 注入封面与歌词（失败不影响下载本身）
            injectTags(context, repo, file, song, mime)

            val saved = save(context, buildFileName(song, mime), mime, file)
            saved.onSuccess { LxLog.i("LxDownload: done ${song.title} -> $it") }
                .onFailure { LxLog.w(it, "LxDownload: write failed ${song.title}") }
            saved
        } catch (e: Exception) {
            LxLog.e(e, "LxDownload: failed ${song.title}")
            Result.failure(e)
        } finally {
            runCatching { temp?.delete() }
            inFlight.remove(song.uid)
        }
    }

    // ------------------------------------------------------------ 元数据注入

    /**
     * 把歌曲信息、封面、歌词写进音频文件的标签里。
     *
     * 封面取 `lxPicUrl`（搜索阶段已带回），歌词走 [LxRepository.fetchLyric]（内置歌词）。
     * 任一步失败都只是少写一个字段，不影响下载结果。
     */
    private suspend fun injectTags(
        context: Context,
        repo: LxRepository,
        file: File,
        song: Song,
        mime: String,
    ) {
        // 歌词：内置歌词可能较慢，失败就算了
        val lyric = runCatching { repo.fetchLyric(song)?.lyric }.getOrNull()
        val coverBytes = song.lxPicUrl?.takeIf { it.isNotBlank() }?.let {
            runCatching { fetchBytes(it) }.getOrNull()
        }

        runCatching {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault

            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist.ifBlank { "未知艺术家" })
            if (song.album.isNotBlank()) tag.setField(FieldKey.ALBUM, song.album)
            if (!lyric.isNullOrBlank()) tag.setField(FieldKey.LYRICS, lyric)

            if (coverBytes != null && coverBytes.size > 512) {
                val art = StandardArtwork()
                art.binaryData = coverBytes
                art.mimeType = sniffImageMime(coverBytes)
                art.description = "Cover"
                art.pictureType = 3 // Cover (front)
                runCatching { tag.deleteArtworkField() }
                tag.setField(art)
            }

            audio.commit()
            LxLog.i(
                "LxDownload: tags ok title=${song.title} " +
                    "cover=${coverBytes?.size ?: 0}B lyric=${lyric?.length ?: 0}字符",
            )
        }.onFailure { LxLog.w(it, "LxDownload: 标签写入失败（不影响文件）") }
    }

    /** 从网络取二进制（封面用），限制大小避免写入超大图。 */
    private fun fetchBytes(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body
            if (body.contentLength() > MAX_COVER_BYTES) return null
            val bytes = body.bytes()
            return bytes.takeIf { it.size <= MAX_COVER_BYTES }
        }
    }

    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        else -> "image/jpeg"
    }

    // ------------------------------------------------------------ 落盘

    private fun save(context: Context, fileName: String, mime: String, source: File): Result<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Lune")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(IOException("MediaStore 插入失败"))

            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("打不开输出流")
            }
            if (written.isFailure) {
                runCatching { resolver.delete(uri, null, null) }
                return Result.failure(written.exceptionOrNull() ?: IOException("写入失败"))
            }
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            runCatching { resolver.update(uri, done, null, null) }
            Result.success(uri.toString())
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Lune",
            )
            if (!dir.exists() && !dir.mkdirs()) {
                return Result.failure(IOException("创建目录失败"))
            }
            val target = File(dir, fileName)
            runCatching {
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }.fold(
                onSuccess = {
                    MediaScannerConnection.scanFile(
                        context, arrayOf(target.absolutePath), arrayOf(mime), null,
                    )
                    Result.success(target.absolutePath)
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private fun extensionOf(mime: String): String = when {
        mime.contains("flac") -> "flac"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.contains("ogg") || mime.contains("opus") -> "ogg"
        mime.contains("wav") -> "wav"
        else -> "mp3"
    }

    private fun buildFileName(song: Song, mime: String): String {
        val base = "${song.artist} - ${song.title}"
            .replace(Regex("""[\\/:*?"<>|\r\n]"""), "_")
            .trim()
            .take(80)
        return "${base.ifBlank { "online_${song.songmid ?: "unknown"}" }}.${extensionOf(mime)}"
    }

    private const val MAX_COVER_BYTES = 5L * 1024 * 1024

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
}
