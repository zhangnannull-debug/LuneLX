package com.lunelx.music.tools.playlist

import com.lunelx.music.tools.Song

/**
 * 队列里的「置顶 / 移到末尾」操作。
 *
 * 一律按 [Song.uid] 匹配：在线歌曲的 MediaStore id 恒为 0，
 * 按 id 查找会命中队列里第一首在线歌 —— 用户拖 A 歌，实际移动到的是 B 歌的位置。
 */
object QueueManager {

    fun moveToFront(playlist: List<Song>, currentSong: Song, targetSong: Song, frontCount: Int = 0): List<Song> {
        val currentIdx = playlist.indexOfFirst { it.uid == currentSong.uid }
        if (currentIdx == -1) return playlist

        val targetIdx = playlist.indexOfFirst { it.uid == targetSong.uid }
        val mutable = playlist.toMutableList()

        if (targetIdx != -1) {
            mutable.removeAt(targetIdx)
        }

        val newCurrentIdx = mutable.indexOfFirst { it.uid == currentSong.uid }
        if (newCurrentIdx == -1) return playlist

        val insertAt = (newCurrentIdx + 1 + frontCount).coerceIn(0, mutable.size)
        mutable.add(insertAt, targetSong)
        return mutable
    }

    fun moveToEnd(playlist: List<Song>, currentSong: Song, targetSong: Song): List<Song> {
        val currentIdx = playlist.indexOfFirst { it.uid == currentSong.uid }
        val targetIdx = playlist.indexOfFirst { it.uid == targetSong.uid }
        if (targetIdx == -1 || currentIdx == -1 || targetIdx == currentIdx) return playlist

        val mutable = playlist.toMutableList()
        mutable.removeAt(targetIdx)
        mutable.add(targetSong)
        return mutable
    }
}
