package com.lunelx.music.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 偏好里的「歌曲间转移」记录。
 *
 * key 必须是 uid：在线歌曲的 MediaStore id 恒为 0，
 * 用 id 记录会把所有在线歌的转移关系糊成一团（A→B 与 A→C 无法区分）。
 */
class SongInteractionTest {

    @Test
    fun transitionRecordsUseUidKeys() {
        val interaction = SongInteraction(songUid = "lx:wy:aaa")
        interaction.recordTransitionTo("local:2")
        interaction.recordTransitionTo("local:2")
        interaction.recordTransitionTo("lx:kg:bbb")

        val map = interaction.nextSongTransitions!!
        assertEquals(2, map["local:2"])
        assertEquals(1, map["lx:kg:bbb"])
        // 老的数字 key 不应该再出现
        assertTrue(map.keys.none { it.toLongOrNull() != null })
    }

    @Test
    fun transitionProbabilityReflectsCounts() {
        val interaction = SongInteraction(songUid = "local:1")
        interaction.recordTransitionTo("local:2")
        interaction.recordTransitionTo("local:2")
        interaction.recordTransitionTo("local:3")

        assertEquals(2f / 3f, interaction.getTransitionProbabilityTo("local:2"), 0.0001f)
        assertEquals(1f / 3f, interaction.getTransitionProbabilityTo("local:3"), 0.0001f)
        assertEquals(0f, interaction.getTransitionProbabilityTo("local:999"), 0.0001f)
    }

    @Test
    fun onlineAndLocalTransitionsDoNotCollide() {
        // 若还用 id 当 key，这两首（id 都是 0）的转移目标会撞在一起
        val a = SongInteraction(songUid = "lx:wy:0")
        val b = SongInteraction(songUid = "lx:kg:0")
        a.recordTransitionTo("local:5")
        b.recordTransitionTo("local:6")
        assertEquals(1f, a.getTransitionProbabilityTo("local:5"), 0.0001f)
        assertEquals(0f, a.getTransitionProbabilityTo("local:6"), 0.0001f)
        assertEquals(1f, b.getTransitionProbabilityTo("local:6"), 0.0001f)
    }
}
