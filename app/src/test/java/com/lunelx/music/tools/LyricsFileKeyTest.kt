package com.lunelx.music.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 歌词缓存文件名的键。
 *
 * 老代码用 `${song.id}.lrc`：在线歌曲 id 恒为 0，所以**所有在线歌共用同一个 `0.lrc`**，
 * 谁最后播放谁就覆盖别人的自定义歌词。改成按 uid 命名后必须验证：
 * 1. 不同歌（尤其是同一 id 的两首在线歌）得到不同文件名；
 * 2. 文件名里不残留冒号等对文件系统不友好的字符。
 */
class LyricsFileKeyTest {

    @Test
    fun localAndOnlineKeysDiffer() {
        val local = LyricsStorageManager.uidFileKeyOf(SongUid.of(7L, null, null))
        val online = LyricsStorageManager.uidFileKeyOf(SongUid.of(0L, "wy", "0039MnYb"))
        assertEquals("local_7", local)
        assertEquals("lx_wy_0039MnYb", online)
        assertNotEquals(local, online)
    }

    @Test
    fun twoOnlineSongsNeverShareTheLegacyZeroFile() {
        val a = LyricsStorageManager.uidFileKeyOf(SongUid.of(0L, "wy", "aaa"))
        val b = LyricsStorageManager.uidFileKeyOf(SongUid.of(0L, "kg", "bbb"))
        assertNotEquals(a, b)
        assertNotEquals("0", a)
        assertNotEquals("0", b)
    }

    @Test
    fun keyContainsNoSeparators() {
        val key = LyricsStorageManager.uidFileKeyOf("lx:tx:a:b/c\\d")
        assertFalse(key.contains(":"))
        assertFalse(key.contains("/"))
        assertFalse(key.contains("\\"))
    }
}
