package com.lunelx.music.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

data class LyricBackupItem(
    val songTitle: String,
    val songArtist: String,
    val lrcContent: String
)

class LyricsStorageManager private constructor(private val context: Context) {

    private val lyricsDir: File by lazy {
        File(context.filesDir, "lyrics").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun sanitizeForFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }

    /**
     * 歌词缓存的文件名按 **uid** 生成：`local_123.lrc` / `lx_wy_0039MnYb0qxYhV.lrc`。
     *
     * 以前是 `${song.id}.lrc`，而在线歌的 id 恒为 0 → 所有在线歌共用同一个 `0.lrc`：
     * A 歌的歌词会覆盖 B 歌，B 歌打开就显示 A 的歌词。
     */
    fun uidFileKey(uid: String): String = uidFileKeyOf(uid)

    private fun uidFile(song: Song): File = File(lyricsDir, "${uidFileKey(song.uid)}.lrc")

    /** 老版本按 MediaStore id 命名的文件，仅本地歌曲有意义（在线歌都叫 0.lrc）。 */
    private fun legacyIdFile(song: Song): File? =
        if (song.isOnline) null else File(lyricsDir, "${song.id}.lrc")

    private fun readLyricsFile(file: File?): String? {
        if (file == null || !file.exists() || !file.isFile || file.length() <= 0L) return null
        return try {
            CharsetUtils.readText(file).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("LyricsStorageManager", "Error reading lyrics file: ${file.name}", e)
            null
        }
    }

    fun getCustomLyrics(song: Song): String? {
        // 1. 按 uid
        readLyricsFile(uidFile(song))?.let { return it }

        // 2. 按老的 MediaStore id（本机升级前的缓存）
        readLyricsFile(legacyIdFile(song))?.let { return it }

        // 3. 按 Artist + Title
        return readLyricsFile(File(lyricsDir, "${sanitizeForFileName("${song.artist}_${song.title}")}.lrc"))
    }

    fun saveLyrics(song: Song, content: String): Boolean {
        return try {
            if (!lyricsDir.exists()) lyricsDir.mkdirs()

            // Ensure title/artist tags are included if not present
            val processedContent = ensureMetadataHeaders(content, song.title, song.artist)

            // Save by uid
            uidFile(song).writeText(processedContent, Charsets.UTF_8)

            // Save by Artist_Title
            val artistTitleFile = File(lyricsDir, "${sanitizeForFileName("${song.artist}_${song.title}")}.lrc")
            artistTitleFile.writeText(processedContent, Charsets.UTF_8)

            // Trigger auto-sync backup if active
            PlaylistBackupManager(context).triggerAutoSync()
            true
        } catch (e: Exception) {
            Log.e("LyricsStorageManager", "Error saving lyrics for ${song.title}", e)
            false
        }
    }

    fun deleteCustomLyrics(song: Song): Boolean {
        return try {
            var deleted = false
            uidFile(song).takeIf { it.exists() }?.let { deleted = it.delete() || deleted }
            legacyIdFile(song)?.takeIf { it.exists() }?.let { deleted = it.delete() || deleted }
            val artistTitleFile = File(lyricsDir, "${sanitizeForFileName("${song.artist}_${song.title}")}.lrc")
            if (artistTitleFile.exists()) {
                deleted = artistTitleFile.delete() || deleted
            }

            // Trigger auto-sync backup if active
            PlaylistBackupManager(context).triggerAutoSync()
            deleted
        } catch (e: Exception) {
            Log.e("LyricsStorageManager", "Error deleting lyrics for ${song.title}", e)
            false
        }
    }

    fun hasCustomLyrics(song: Song): Boolean {
        if (readLyricsFile(uidFile(song)) != null) return true
        if (readLyricsFile(legacyIdFile(song)) != null) return true
        val artistTitleFile = File(lyricsDir, "${sanitizeForFileName("${song.artist}_${song.title}")}.lrc")
        return artistTitleFile.exists() && artistTitleFile.length() > 0
    }

    fun getAllCustomLyrics(): List<LyricBackupItem> {
        val results = mutableListOf<LyricBackupItem>()
        val seenKey = mutableSetOf<String>()

        val files = lyricsDir.listFiles { f -> f.isFile && f.name.endsWith(".lrc", ignoreCase = true) }
            ?: return emptyList()

        for (file in files) {
            try {
                val content = CharsetUtils.readText(file)
                if (content.isBlank()) continue

                val (title, artist) = extractTitleAndArtist(content, file.nameWithoutExtension)
                val key = "${artist.lowercase()}_${title.lowercase()}"
                if (key !in seenKey && title.isNotBlank()) {
                    seenKey.add(key)
                    results.add(LyricBackupItem(songTitle = title, songArtist = artist, lrcContent = content))
                }
            } catch (e: Exception) {
                Log.e("LyricsStorageManager", "Failed to parse lyric file for backup: ${file.name}", e)
            }
        }
        return results
    }

    fun restoreCustomLyrics(items: List<LyricBackupItem>, allSongs: List<Song> = emptyList()) {
        if (!lyricsDir.exists()) lyricsDir.mkdirs()

        val songsLookup = allSongs.associateBy {
            "${it.artist.trim().lowercase()}_${it.title.trim().lowercase()}"
        }

        for (item in items) {
            try {
                val content = ensureMetadataHeaders(item.lrcContent, item.songTitle, item.songArtist)

                // Save by Artist_Title
                val artistTitleFile = File(lyricsDir, "${sanitizeForFileName("${item.songArtist}_${item.songTitle}")}.lrc")
                artistTitleFile.writeText(content, Charsets.UTF_8)

                // If matched to a library song, also save by uid
                val key = "${item.songArtist.trim().lowercase()}_${item.songTitle.trim().lowercase()}"
                songsLookup[key]?.let { matchedSong ->
                    val uidTarget = File(lyricsDir, "${uidFileKey(matchedSong.uid)}.lrc")
                    uidTarget.writeText(content, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e("LyricsStorageManager", "Error restoring lyrics for ${item.songTitle}", e)
            }
        }
    }

    private fun ensureMetadataHeaders(content: String, title: String, artist: String): String {
        var result = content
        val hasTitle = Pattern.compile("^\\[ti:.*?\\]", Pattern.CASE_INSENSITIVE or Pattern.MULTILINE).matcher(result).find()
        val hasArtist = Pattern.compile("^\\[ar:.*?\\]", Pattern.CASE_INSENSITIVE or Pattern.MULTILINE).matcher(result).find()

        val prefixBuilder = java.lang.StringBuilder()
        if (!hasTitle && title.isNotBlank()) {
            prefixBuilder.append("[ti:").append(title).append("]\n")
        }
        if (!hasArtist && artist.isNotBlank()) {
            prefixBuilder.append("[ar:").append(artist).append("]\n")
        }

        return prefixBuilder.toString() + result
    }

    private fun extractTitleAndArtist(content: String, fallbackName: String): Pair<String, String> {
        var title = ""
        var artist = ""

        val tiMatcher = Pattern.compile("\\[ti:\\s*(.*?)\\]", Pattern.CASE_INSENSITIVE).matcher(content)
        if (tiMatcher.find()) {
            title = tiMatcher.group(1)?.trim() ?: ""
        }

        val arMatcher = Pattern.compile("\\[ar:\\s*(.*?)\\]", Pattern.CASE_INSENSITIVE).matcher(content)
        if (arMatcher.find()) {
            artist = arMatcher.group(1)?.trim() ?: ""
        }

        if (title.isBlank()) {
            val parts = fallbackName.split("_")
            if (parts.size >= 2) {
                artist = parts[0]
                title = parts.drop(1).joinToString("_")
            } else {
                title = fallbackName
            }
        }

        return Pair(title, artist)
    }

    companion object {
        @Volatile
        private var instance: LyricsStorageManager? = null

        fun getInstance(context: Context): LyricsStorageManager {
            return instance ?: synchronized(this) {
                instance ?: LyricsStorageManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 歌词缓存的文件名（不含 `.lrc`）：由 uid 转义而来 —— `local:123` → `local_123`。
         *
         * 纯字符串逻辑，放在 companion 里以便脱离 Context 做单元测试。
         */
        fun uidFileKeyOf(uid: String): String =
            uid.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)

        fun formatLrcTimestamp(timeMs: Long): String {            val safeMs = timeMs.coerceAtLeast(0L)
            val totalSeconds = safeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val centis = (safeMs % 1000) / 10
            return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
        }

        private val TIME_TAG_REGEX = Regex("[\\[<]\\d{1,2}:\\d{2}[.:]\\d{1,3}[\\]>]")

        fun stripTimestamps(rawLyrics: String): String {
            return rawLyrics.lines()
                .filterNot { it.startsWith("[ti:") || it.startsWith("[ar:") || it.startsWith("[al:") || it.startsWith("[by:") || it.startsWith("[offset:") }
                .map { line -> TIME_TAG_REGEX.replace(line, "").trim() }
                .joinToString("\n")
                .trim()
        }
    }
}
