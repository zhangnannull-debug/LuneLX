package com.lunelx.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lunelx.music.R
import com.lunelx.music.data.PlaybackStats
import com.lunelx.music.data.Playlist
import com.lunelx.music.tools.Song
import com.lunelx.music.tools.SongJson
import com.lunelx.music.tools.SongUid
import com.lunelx.music.ui.screens.resume.HeroSection
import com.lunelx.music.ui.screens.resume.PlaylistGridSection
import com.lunelx.music.ui.screens.resume.RecommendationSection
import com.lunelx.music.ui.screens.resume.RecentlyAddedSection
import com.lunelx.music.ui.screens.resume.SectionHeader
import com.lunelx.music.ui.screens.resume.TopArtistsSection
import com.lunelx.music.ui.screens.resume.ArtistItem
import com.lunelx.music.ui.screens.resume.TopGenresSection
import com.lunelx.music.ui.screens.resume.GenreItem
import com.lunelx.music.ui.viewmodels.MusicViewModel

@Composable
fun ResumeScreen(
    viewModel: MusicViewModel,
    allSongs: List<Song>,
    allPlaylists: List<Playlist>,
    bottomPadding: Dp,
    currentSong: Song?,
    isPlaying: Boolean,
    playbackProgress: Float = 0f,
    hasBlurBackground: Boolean = false,
    isDarkTheme: Boolean = false,
    useCustomControlsColor: Boolean = false,
    controlsColorPalette: Int = 0,
    onSongClick: (Song, List<Song>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onArtistClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onExpandPlayer: () -> Unit,
    onPlayToggle: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val sUnknownArtist = stringResource(R.string.unknown_artist)
    val sUnknownSong = stringResource(R.string.unknown_song)

    val fallbackTopArtistName = remember(allSongs) {
        allSongs.filter { it.artist.isNotBlank() && !it.artist.equals("<unknown>", ignoreCase = true) }
            .groupingBy { it.artist.trim() }
            .eachCount()
            .maxByOrNull { it.value }?.key
    }

    val fallbackTopPlaylist = remember(allPlaylists, viewModel.playlistMappings) {
        allPlaylists.maxByOrNull { playlist ->
            viewModel.getSongsForPlaylistSync(playlist.id).size
        }
    }

    // 统计行要和它对应的歌绑在一起，「标题来自 A、播放次数来自 B」曾经就是这么来的：
    // 标题取的是「第一条能解析出歌的统计」，次数取的却是「原始第一条统计」。
    val top3SongStats = remember(viewModel.topSongStats, allSongs) {
        viewModel.topSongStats
            .mapNotNull { stat -> resolveStatsSong(stat, allSongs)?.let { song -> song to stat } }
            .take(3)
    }
    val top3Songs = top3SongStats.map { it.first }
    val topSong = top3Songs.firstOrNull()

    val realTopArtistName = remember(viewModel.topArtistStats, viewModel.topSongStats, allSongs, fallbackTopArtistName) {
        val artistPlaysMap = mutableMapOf<String, Long>()

        // 1. Accumulate plays from top played songs
        for (stat in viewModel.topSongStats) {
            val s = resolveStatsSong(stat, allSongs)
            if (s != null && stat.playCount > 0) {
                val art = s.artist.trim()
                if (art.isNotBlank() && !art.equals("<unknown>", ignoreCase = true)) {
                    artistPlaysMap[art] = (artistPlaysMap[art] ?: 0L) + stat.playCount
                }
            }
        }

        // 2. Combine with direct ARTIST_ stats
        for (stat in viewModel.topArtistStats) {
            val art = stat.id.removePrefix("ARTIST_").trim()
            if (art.isNotBlank() && !art.equals("<unknown>", ignoreCase = true) && stat.playCount > 0) {
                val existing = artistPlaysMap[art] ?: 0L
                artistPlaysMap[art] = maxOf(existing, stat.playCount)
            }
        }

        // 3. Find artist with highest playCount
        val bestByPlays = artistPlaysMap.filter { it.value > 0 }.maxByOrNull { it.value }?.key

        bestByPlays
            ?: viewModel.topArtistStats.firstOrNull { it.playCount > 0 }?.id?.removePrefix("ARTIST_")?.trim()
            ?: fallbackTopArtistName
            ?: sUnknownArtist
    }

    val recommendations = remember(allSongs) {
        val topArtists = allSongs.groupingBy { it.artist.trim() }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        allSongs.filter { topArtists.contains(it.artist.trim()) }
            .distinctBy { it.id }
            .shuffled()
            .take(20)
    }

    val topPlaylists = remember(allPlaylists) {
        allPlaylists.take(10)
    }

    val topArtistsList = remember(allSongs, viewModel.topArtistStats, viewModel.topSongStats) {
        val artistPlaysMap = mutableMapOf<String, Long>()
        for (stat in viewModel.topSongStats) {
            val s = resolveStatsSong(stat, allSongs)
            if (s != null && stat.playCount > 0) {
                val art = s.artist.trim()
                if (art.isNotBlank() && !art.equals("<unknown>", ignoreCase = true)) {
                    artistPlaysMap[art] = (artistPlaysMap[art] ?: 0L) + stat.playCount
                }
            }
        }
        for (stat in viewModel.topArtistStats) {
            val art = stat.id.removePrefix("ARTIST_").trim()
            if (art.isNotBlank() && !art.equals("<unknown>", ignoreCase = true) && stat.playCount > 0) {
                val existing = artistPlaysMap[art] ?: 0L
                artistPlaysMap[art] = maxOf(existing, stat.playCount)
            }
        }

        allSongs.filter { it.artist.isNotBlank() && !it.artist.equals("<unknown>", ignoreCase = true) }
            .groupBy { it.artist.trim() }
            .entries
            .map { (artistName, songs) ->
                val playCount: Int = (artistPlaysMap[artistName] ?: songs.size.toLong()).toInt()
                val coverSong = songs.firstOrNull { !it.coverUrl.isNullOrEmpty() || it.albumArtUri != null } ?: songs.firstOrNull()
                val coverStr: String? = coverSong?.coverUrl ?: coverSong?.albumArtUri?.toString()
                ArtistItem(
                    name = artistName,
                    playCount = playCount,
                    coverUrl = coverStr,
                    songs = songs
                )
            }
            .sortedByDescending { it.playCount }
    }

    val topGenresList = remember(allSongs) {
        allSongs.groupBy { song ->
            val g = song.genre?.trim()
            if (g.isNullOrEmpty() || g.equals("<unknown>", ignoreCase = true) || g.equals("unknown", ignoreCase = true)) {
                "Desconocido"
            } else {
                g
            }
        }.entries.map { (genreName, songs) ->
            val coverSong = songs.firstOrNull { !it.coverUrl.isNullOrEmpty() || it.albumArtUri != null } ?: songs.firstOrNull()
            val coverStr: String? = coverSong?.coverUrl ?: coverSong?.albumArtUri?.toString()
            GenreItem(
                name = genreName,
                songCount = songs.size,
                coverUrl = coverStr,
                songs = songs
            )
        }.sortedByDescending { it.songCount }
    }

    val recentlyAdded = remember(allSongs) {
        allSongs.sortedByDescending { it.dateAdded }.take(10)
    }

    val topArtistSongs = remember(allSongs, realTopArtistName) {
        allSongs.filter { it.artist.trim().equals(realTopArtistName.trim(), ignoreCase = true) }
    }

    val topGenreStat = viewModel.topGenreStats.firstOrNull()
    val realTopGenreName = remember(topGenreStat, topGenresList) {
        topGenreStat?.id?.replace("GENRE_", "") ?: (topGenresList.firstOrNull()?.name ?: "")
    }

    val totalPlays = remember(viewModel.topSongStats, allSongs) {
        // 只统计能解析成歌的行：解析不出来的（例如历史遗留的 `SONG_local:0`）不该进总数
        viewModel.topSongStats
            .mapNotNull { stat -> resolveStatsSong(stat, allSongs)?.let { stat } }
            .sumOf { it.playCount }
    }
    // 播放次数必须取自「标题那首歌」对应的那一行，不能直接拿 topSongStats.first()
    val topSongStat = top3SongStats.firstOrNull()?.second

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = 8.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + slideInVertically { it / 2 }
        ) {
            HeroSection(
                currentSong = currentSong,
                isPlaying = isPlaying,
                topArtist = realTopArtistName,
                topArtistSongs = topArtistSongs,
                topSong = topSong,
                topSongPlayCount = topSongStat?.playCount ?: 0L,
                topGenre = realTopGenreName,
                totalPlays = totalPlays,
                onArtistClick = onArtistClick,
                onSongClick = { song -> onSongClick(song, allSongs) },
                hasBlurBackground = hasBlurBackground,
                isDarkTheme = isDarkTheme,
                useCustomControlsColor = useCustomControlsColor,
                controlsColorPalette = controlsColorPalette,
                playbackProgress = playbackProgress,
                onContinueListening = onExpandPlayer,
                onPlayToggle = onPlayToggle
            )
        }

        if (recommendations.isNotEmpty()) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                RecommendationSection(
                    title = stringResource(R.string.resume_recommendations),
                    songs = recommendations,
                    hasBlurBackground = hasBlurBackground,
                    isDarkTheme = isDarkTheme,
                    useCustomControlsColor = useCustomControlsColor,
                    controlsColorPalette = controlsColorPalette,
                    onSongClick = { song -> onSongClick(song, allSongs) }
                )
            }
        }

        if (topPlaylists.isNotEmpty()) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                PlaylistGridSection(
                    viewModel = viewModel,
                    playlists = topPlaylists,
                    hasBlurBackground = hasBlurBackground,
                    onPlaylistClick = onPlaylistClick
                )
            }
        }

        if (topArtistsList.isNotEmpty()) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                TopArtistsSection(
                    artists = topArtistsList,
                    hasBlurBackground = hasBlurBackground,
                    onArtistClick = onArtistClick
                )
            }
        }

        if (topGenresList.isNotEmpty()) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                TopGenresSection(
                    genres = topGenresList,
                    hasBlurBackground = hasBlurBackground,
                    onGenreClick = onGenreClick
                )
            }
        }

        if (recentlyAdded.isNotEmpty()) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                RecentlyAddedSection(
                    songs = recentlyAdded,
                    hasBlurBackground = hasBlurBackground,
                    onSongClick = { song -> onSongClick(song, allSongs) }
                )
            }
        }
    }
}

/**
 * 把一条 `SONG_<uid>` 统计行还原成一首歌。
 *
 * - 统计行的 key 是 **uid**（`SONG_local:123` / `SONG_lx:wy:0039...`），不是 MediaStore id。
 * - 本地歌：按 uid 在音乐库里反查。
 * - 在线歌：库里没有，用写进行里的 Song 快照（[PlaybackStats.meta]）重建 ——
 *   否则「最常播放」永远只能显示本地歌，在线歌的播放记录等于白记。
 */
private fun resolveStatsSong(stat: PlaybackStats, allSongs: List<Song>): Song? {
    val uid = SongUid.uidFromStatsId(stat.id) ?: return null
    // `local:0` 是历史遗留行：旧版本用 MediaStore id 当统计 key，而在线歌 id 恒为 0，
    // 于是所有在线歌的播放次数都堆在一行 `SONG_0` 里（实测 203 次）。
    // 这一行没有任何单曲意义，也无法拆分，直接不参与展示。MediaStore id 从 1 开始。
    if (SongUid.localIdOf(uid) == 0L) return null
    return allSongs.find { it.uid == uid } ?: SongJson.decode(stat.meta)
}
