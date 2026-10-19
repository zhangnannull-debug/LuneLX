package com.lunelx.music.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SongUid] 的单元测试。
 *
 * uid 是混合队列的基石：本地歌曲与在线歌曲必须产生互不冲突的标识，
 * 否则队列去重、当前播放高亮、状态恢复都会错乱。
 *
 * 这里只测纯字符串逻辑，不触碰任何 Android 类。
 */
class SongUidTest {

    @Test
    fun localUidUsesLocalPrefix() {
        assertEquals("local:12345", SongUid.of(12345L, null, null))
    }

    @Test
    fun onlineUidUsesLxPrefix() {
        assertEquals("lx:wy:0039MnYb0qxYhV", SongUid.of(0L, "wy", "0039MnYb0qxYhV"))
    }

    @Test
    fun onlineSongsNeverCollideDespiteSameLocalId() {
        // 所有在线歌曲的 MediaStore ID 都是 0，但 uid 必须互不相同
        val a = SongUid.of(0L, "wy", "aaa")
        val b = SongUid.of(0L, "tx", "bbb")
        assertNotEquals(a, b)
    }

    @Test
    fun sameOnlineSongHasStableUid() {
        assertEquals(
            SongUid.of(0L, "kg", "hash123"),
            SongUid.of(0L, "kg", "hash123"),
        )
    }

    @Test
    fun localAndOnlineNeverCollide() {
        assertNotEquals(SongUid.of(0L, null, null), SongUid.of(0L, "wy", "0"))
    }

    @Test
    fun partialOnlineInfoFallsBackToLocal() {
        // 只有 source 没有 songmid，不能算在线
        assertEquals("local:7", SongUid.of(7L, "wy", null))
        // 只有 songmid 没有 source，同样不算
        assertEquals("local:7", SongUid.of(7L, null, "abc"))
    }

    @Test
    fun parseLocalUidBackToId() {
        assertEquals(999L, SongUid.localIdOf("local:999"))
        assertNull(SongUid.localIdOf("lx:wy:abc"))
        assertNull(SongUid.localIdOf("garbage"))
        assertNull(SongUid.localIdOf("local:notanumber"))
    }

    @Test
    fun parseLxUidBackToSourceAndSongmid() {
        assertEquals("wy" to "0039MnYb", SongUid.lxOf("lx:wy:0039MnYb"))
        // songmid 里允许出现冒号，必须只按第一个冒号切分
        assertEquals("tx" to "a:b:c", SongUid.lxOf("lx:tx:a:b:c"))
        assertNull(SongUid.lxOf("local:1"))
        assertNull(SongUid.lxOf("lx:"))
        assertNull(SongUid.lxOf("lx:wy:"))
        assertNull(SongUid.lxOf("lx::nomid"))
    }

    @Test
    fun uidRoundTripsThroughParsers() {
        val local = SongUid.of(42L, null, null)
        assertEquals(42L, SongUid.localIdOf(local))

        val online = SongUid.of(0L, "kw", "MUSIC_123")
        val parsed = SongUid.lxOf(online)
        assertEquals("kw", parsed?.first)
        assertEquals("MUSIC_123", parsed?.second)
    }

    @Test
    fun prefixPredicates() {
        assertTrue(SongUid.isLocalUid("local:1"))
        assertFalse(SongUid.isLocalUid("lx:wy:1"))
        assertTrue(SongUid.isOnlineUid("lx:wy:1"))
        assertFalse(SongUid.isOnlineUid("local:1"))
    }

    // ---------------------------------------------------------------
    // 播放统计行 key
    // ---------------------------------------------------------------

    @Test
    fun statsIdWrapsUidAndRoundTrips() {
        val local = SongUid.of(12345L, null, null)
        assertEquals("SONG_local:12345", SongUid.statsIdOf(local))
        assertEquals(local, SongUid.uidFromStatsId("SONG_local:12345"))

        // 在线歌：uid 里带冒号，反解时必须原样还原（不能按冒号切）
        val online = SongUid.of(0L, "wy", "0039MnYb")
        assertEquals("SONG_lx:wy:0039MnYb", SongUid.statsIdOf(online))
        assertEquals(online, SongUid.uidFromStatsId("SONG_lx:wy:0039MnYb"))
    }

    @Test
    fun statsIdsOfDifferentOnlineSongsNeverCollide() {
        // 这是老 bug 的核心：以前统计行是 SONG_<id>，而在线歌 id 恒为 0，
        // 于是任意两首在线歌都写进 "SONG_0"
        val a = SongUid.statsIdOf(SongUid.of(0L, "wy", "aaa"))
        val b = SongUid.statsIdOf(SongUid.of(0L, "kg", "bbb"))
        assertNotEquals(a, b)
        assertNotEquals("SONG_0", a)
        assertNotEquals("SONG_0", b)
    }

    @Test
    fun uidFromStatsIdRejectsOtherStatTypes() {
        assertNull(SongUid.uidFromStatsId("ARTIST_Queen"))
        assertNull(SongUid.uidFromStatsId("PLAYLIST_7"))
        assertNull(SongUid.uidFromStatsId("SONG_"))
    }

    // ---------------------------------------------------------------
    // 老数据（纯数字 MediaStore id）迁移
    // ---------------------------------------------------------------

    @Test
    fun legacyNumericKeyBecomesLocalUid() {
        assertEquals("local:42", SongUid.fromLegacyIdKey("42"))
        assertEquals("local:0", SongUid.fromLegacyIdKey("0"))
    }

    @Test
    fun alreadyMigratedOrForeignKeyIsNotLegacy() {
        assertNull(SongUid.fromLegacyIdKey("local:42"))
        assertNull(SongUid.fromLegacyIdKey("lx:wy:42"))
        assertNull(SongUid.fromLegacyIdKey("ARTIST_Queen"))
        assertNull(SongUid.fromLegacyIdKey(""))
    }
}
