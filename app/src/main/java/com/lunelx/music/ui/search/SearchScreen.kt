package com.lunelx.music.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.lunelx.music.R
import com.lunelx.music.data.Playlist
import com.lunelx.music.tools.PlaybackManager
import com.lunelx.music.tools.SettingsManager
import com.lunelx.music.tools.Song
import com.lunelx.music.tools.normalizeForSearch
import androidx.compose.foundation.lazy.rememberLazyListState
import com.lunelx.music.ui.components.AppBlurBackdrop
import com.lunelx.music.ui.components.ScrollToTopPill
import com.lunelx.music.ui.components.rememberScrollToTopVisibility
import com.lunelx.music.ui.components.SongItem
import com.lunelx.music.ui.components.rememberBlurSheetColors
import com.lunelx.music.ui.data.Album
import com.lunelx.music.ui.playlist.PlaylistPreviewCovers
import com.lunelx.music.ui.utils.bounceClick
import com.lunelx.music.ui.utils.fastSmoothScrollToItem
import com.lunelx.music.ui.viewmodels.MusicViewModel
import com.lunelx.music.lx.LxRepository
import com.lunelx.music.lx.engine.LxSources
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchResults(
    val songs: List<Song>,
    val favoriteSongs: List<Song>,
    val albumResults: Map<Album, List<Song>>,
    val playlistResults: Map<Playlist, List<Song>>,
    val tagResults: Map<String, List<Song>>,
    val realAlbumResults: Map<Album, List<Song>> = emptyMap()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MusicViewModel,
    allFolders: List<String>,
    onDismiss: () -> Unit,
    onSongClick: (Song, List<Song>, String, Long) -> Unit,
    onPlayAll: (List<Song>, Boolean) -> Unit,
    onNavigateToAlbum: (Album) -> Unit,
    onNavigateToPlaylist: (Playlist) -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onOptionsClick: (Song) -> Unit,
    currentlyPlayingId: Long?,
    activeCategory: String?,
    activePlaylistId: Long?,
    onFavoriteClick: ((Song) -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val pm = PlaybackManager.getInstance(context)
    val settings = remember { SettingsManager.getInstance(context) }

    // ---------- 同一个搜索框里切换「本地 / 在线」 ----------
    // 在线搜索走 LxRepository 的内置搜索，结果列表用与本地完全相同的 SongItem 渲染，
    // 这样两个列表的样式天然一致（不需要维护两套行布局）。
    // 模式本身记在设置里：下次打开搜索页直接回到上次用的那个。
    var searchMode by remember {
        mutableStateOf(if (settings.searchOnlineMode) SearchMode.ONLINE else SearchMode.LOCAL)
    }
    val lxRepo = remember { LxRepository.getInstance(context) }
    val onlineSources = remember(lxRepo.capabilities.value, lxRepo.onlineEnabled) {
        lxRepo.searchableSources()
    }
    var onlineSource by remember { mutableStateOf<String?>(null) }
    var onlineResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineSearched by remember { mutableStateOf(false) }

    // ---------- 收藏的在线歌 ----------
    // 在线歌不在音乐库里，重启后只能靠 Room 里存的 Song 快照重建，
    // 所以「收藏的在线歌」这个列表是收藏功能唯一的入口（本地收藏在音乐库的收藏页）。
    var favoriteOnlineSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var favoriteOnlineVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(searchMode, favoriteOnlineVersion, lxRepo.onlineEnabled) {
        if (searchMode != SearchMode.ONLINE || !lxRepo.onlineEnabled) {
            favoriteOnlineSongs = emptyList()
            return@LaunchedEffect
        }
        // 收藏是异步落库的，稍等一下再读，否则刚点的收藏不会出现在列表里
        if (favoriteOnlineVersion > 0) delay(300)
        favoriteOnlineSongs = runCatching { lxRepo.favoriteOnlineSongs() }.getOrDefault(emptyList())
    }

    LaunchedEffect(searchMode, query, onlineSource, onlineSources) {
        if (searchMode != SearchMode.ONLINE) return@LaunchedEffect
        val src = onlineSource ?: onlineSources.firstOrNull()
        if (src == null || query.isBlank()) {
            onlineResults = emptyList()
            onlineSearched = false
            return@LaunchedEffect
        }
        // 输入防抖：边打字边发请求既浪费也会闪烁
        delay(350)
        onlineLoading = true
        onlineResults = runCatching { lxRepo.search(src, query.trim()) }.getOrDefault(emptyList())
        onlineSearched = true
        onlineLoading = false
    }

    val currentSong = pm.currentSong
    val blurColors = rememberBlurSheetColors(currentSong)

    var showFilterDialog by remember { mutableStateOf(false) }
    var filterSongs by remember { mutableStateOf(true) }
    var filterPlaylists by remember { mutableStateOf(true) }
    var filterAlbums by remember { mutableStateOf(true) }
    var filterArtists by remember { mutableStateOf(true) }
    var filterFolders by remember { mutableStateOf(true) }
    var filterFavorites by remember { mutableStateOf(true) }
    val isSearchActive = activePlaylistId == -300L
    var searchShuffle by remember { mutableStateOf(settings.getPlaylistShuffle(-300L)) }

    if (settings.isSectionCustomizationEnabled) {
        val hiddenTabs = settings.hiddenSectionTabs
        if ("ALL" in hiddenTabs) filterSongs = false
        if ("FAVORITES" in hiddenTabs) filterFavorites = false
        if ("PLAYLISTS" in hiddenTabs) filterPlaylists = false
        if ("ALBUMS" in hiddenTabs) filterAlbums = false
        if ("ARTISTS" in hiddenTabs) filterArtists = false
        if ("FOLDERS" in hiddenTabs) filterFolders = false
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) searchShuffle = pm.isShuffle
    }

    val isPlaying = pm.isPlaying
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val allSongs = remember(viewModel.filteredSongs, allFolders) {
        viewModel.filteredSongs.filter { song -> 
            allFolders.contains(song.folderName) 
        }
    }
    val allAlbums = remember(allSongs) {
        allSongs.groupBy { it.artist }
            .map { (artistName, songs) -> 
                Album(
                    id = artistName.hashCode().toLong(), 
                    name = artistName, 
                    artist = "", 
                    albumArtUri = songs.first().albumArtUri, 
                    coverUrl = songs.first().coverUrl, 
                    songs = songs.sortedWith(compareBy({ it.album }, { it.title }))
                )
            }.sortedBy { it.name }
    }
    val allRealAlbums = remember(allSongs) {
        allSongs.groupBy { it.album }
            .map { (albumName, songs) ->
                Album(
                    id = albumName.hashCode().toLong(),
                    name = albumName,
                    artist = songs.first().artist,
                    albumArtUri = songs.first().albumArtUri,
                    coverUrl = songs.first().coverUrl,
                    songs = songs.sortedBy { it.title }
                )
            }.sortedBy { it.name }
    }
    val allPlaylists = viewModel.playlists
    val playlistMappings = viewModel.playlistMappings

    val sTabAll = stringResource(R.string.tab_all)
    val sTabFavorites = stringResource(R.string.tab_favorites)
    val sTabAlbums = stringResource(R.string.tab_albums)
    val sTabAlbumsReal = stringResource(R.string.tab_albums_real)
    val sPlaylists = stringResource(R.string.playlists)
    val sFilterTitle = stringResource(R.string.search_filter)
    val sFilterAll = stringResource(R.string.search_filter_all)
    val sFilterPlaylist = stringResource(R.string.search_filter_playlist)
    val sFilterAlbum = stringResource(R.string.search_filter_album)
    val sFilterArtist = stringResource(R.string.search_filter_artist)
    val sFilterFolder = stringResource(R.string.search_filter_folder)
    val sFilterCancel = stringResource(R.string.search_filter_cancel)

    val searchResults = remember(query, allSongs, allAlbums, allRealAlbums, allPlaylists, allFolders, sTabFavorites, playlistMappings) {
        if (query.isBlank()) return@remember SearchResults(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap())
        
        val normalizedQuery = query.normalizeForSearch()
        val searchTerms = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (searchTerms.isEmpty()) return@remember SearchResults(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap())
        
        val matchedSongs = allSongs.filter { song ->
            val searchTarget = "${song.title} ${song.artist} ${song.album}".normalizeForSearch()
            searchTerms.all { term -> searchTarget.contains(term) }
        }
        val matchedSongIds = matchedSongs.map { it.id }.toHashSet()

        val albumResults = mutableMapOf<Album, List<Song>>()
        allAlbums.forEach { album ->
            val nameMatches = searchTerms.all { term -> album.name.normalizeForSearch().contains(term) }
            val matchingSongs = album.songs.filter { it.id in matchedSongIds }
            if (nameMatches || matchingSongs.isNotEmpty()) {
                albumResults[album] = matchingSongs
            }
        }

        val realAlbumResults = mutableMapOf<Album, List<Song>>()
        allRealAlbums.forEach { album ->
            val nameMatches = searchTerms.all { term -> album.name.normalizeForSearch().contains(term) }
            val matchingSongs = album.songs.filter { it.id in matchedSongIds }
            if (nameMatches || matchingSongs.isNotEmpty()) {
                realAlbumResults[album] = matchingSongs
            }
        }

        val playlistResults = mutableMapOf<Playlist, List<Song>>()
        allPlaylists.forEach { playlist ->
            val nameMatches = searchTerms.all { term -> playlist.name.normalizeForSearch().contains(term) }
            
            val playlistSongs = playlistMappings.filter { it.playlistId == playlist.id }
                .mapNotNull { mapping -> allSongs.find { it.id == mapping.songId } }
                
            val matchingSongs = playlistSongs.filter { it.id in matchedSongIds }
            
            if (nameMatches || matchingSongs.isNotEmpty()) {
                playlistResults[playlist] = matchingSongs
            }
        }

        val favoriteSongs = matchedSongs.filter { it.isFavorite }

        val tagResults = mutableMapOf<String, List<Song>>()
        
        if (favoriteSongs.isNotEmpty()) {
            tagResults[sTabFavorites] = favoriteSongs
        }

        allFolders.forEach { folder ->
            val normalizedFolder = folder.normalizeForSearch()
            if (normalizedFolder == sTabFavorites.normalizeForSearch() || normalizedFolder == "favorites" || normalizedFolder == "favoritos") {
                return@forEach
            }
            val nameMatches = searchTerms.all { term -> normalizedFolder.contains(term) }
            if (nameMatches) {
                val folderSongs = allSongs.filter { it.folderName == folder && it.id in matchedSongIds }
                tagResults[folder] = folderSongs
            }
        }

        matchedSongs.forEach { song ->
            val folder = song.folderName
            if (allFolders.contains(folder) && !tagResults.containsKey(folder)) {
                val folderSongs = allSongs.filter { it.folderName == folder && it.id in matchedSongIds }
                tagResults[folder] = folderSongs
            }
        }

        SearchResults(matchedSongs, favoriteSongs, albumResults, playlistResults, tagResults, realAlbumResults)
    }

    BackHandler(onBack = onDismiss)

    AppBlurBackdrop(
        hasBlurBackground = blurColors.hasBlur,
        isDarkTheme = blurColors.isDark,
        currentSong = currentSong,
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { 
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                                .focusRequester(focusRequester),
                            placeholder = { 
                                Text(
                                    stringResource(R.string.search_hint), 
                                    maxLines = 1, 
                                    overflow = TextOverflow.Ellipsis,
                                    color = blurColors.textSecondaryColor
                                ) 
                            },
                            singleLine = true,
                            trailingIcon = {
                                // 搜索框内的模式切换：本地 ⇄ 在线
                                TextButton(
                                    onClick = {
                                        val next = if (searchMode == SearchMode.LOCAL) {
                                            SearchMode.ONLINE
                                        } else {
                                            SearchMode.LOCAL
                                        }
                                        searchMode = next
                                        // 记住这次选择，下次打开搜索页直接回到这个模式
                                        settings.searchOnlineMode = next == SearchMode.ONLINE
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Icon(
                                        // 显示的是**当前模式**，不是点了会切到哪
                                        imageVector = if (searchMode == SearchMode.LOCAL) {
                                            Icons.Default.PhoneAndroid
                                        } else {
                                            Icons.Default.Language
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (searchMode == SearchMode.LOCAL) "本地" else "在线",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = blurColors.textColor,
                                unfocusedTextColor = blurColors.textColor,
                                cursorColor = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Surface(
                                shape = CircleShape,
                                color = if (blurColors.hasBlur) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack, 
                                        contentDescription = "Back",
                                        tint = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showFilterDialog = true }) {
                            Surface(
                                shape = CircleShape,
                                color = if (blurColors.hasBlur) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.FilterList,
                                        contentDescription = "Filter",
                                        tint = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            // 在线模式：把同一块内容区换成在线结果（行样式复用 SongItem，与本地一致）
            if (searchMode == SearchMode.ONLINE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    OnlineSearchPanel(
                        sources = onlineSources,
                        selectedSource = onlineSource ?: onlineSources.firstOrNull(),
                        onSelectSource = { onlineSource = it },
                        results = onlineResults,
                        favoriteSongs = favoriteOnlineSongs,
                        loading = onlineLoading,
                        searched = onlineSearched,
                        query = query,
                        hasBlur = blurColors.hasBlur,
                        useCustomControlsColor = blurColors.useCustomControlsColor,
                        controlsColorPalette = blurColors.controlsColorPalette,
                        primaryColor = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                        secondaryColor = blurColors.textSecondaryColor,
                        isPlaying = isPlaying,
                        currentUid = pm.currentSong?.uid,
                        onSongClick = { song, list -> onSongClick(song, list, "在线搜索", -300L) },
                        onFavoriteSongClick = { song -> onSongClick(song, favoriteOnlineSongs, "在线收藏", -301L) },
                        onOptionsClick = onOptionsClick,
                        onFavoriteClick = onFavoriteClick?.let { cb ->
                            { song ->
                                cb(song)
                                // 在线结果列表是本页的状态，不随 musicViewModel 更新，
                                // 这里手动镜像一下，否则点完爱心看起来没反应
                                onlineResults = onlineResults.map {
                                    if (it.uid == song.uid) it.copy(isFavorite = !song.isFavorite) else it
                                }
                                // 乐观更新收藏列表，再触发一次落库后重读
                                favoriteOnlineSongs = favoriteOnlineSongs.map {
                                    if (it.uid == song.uid) it.copy(isFavorite = !song.isFavorite) else it
                                }
                                favoriteOnlineVersion++
                            }
                        },
                    )
                }
                return@Scaffold
            }

            val listState = rememberLazyListState()
            val isScrollToTopVisible = rememberScrollToTopVisibility(listState, settings.isScrollToTopEnabled)
            val scope = rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                if (filterSongs && searchResults.songs.isNotEmpty()) {
                    item {
                        Text(
                            text = sTabAll,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onNavigateToFolder("ALL") }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth()
                        )
                    }
                    if (searchResults.songs.size > 1) {
                        item {
                            val btnHeight = 52.dp
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val playBtnTint = if (blurColors.hasBlur) {
                                        if (blurColors.useCustomControlsColor) Color.White else Color.Black
                                    } else {
                                        MaterialTheme.colorScheme.onPrimary
                                    }
                                    Surface(
                                        onClick = { onPlayAll(searchResults.songs, searchShuffle) },
                                        shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                                        color = if (blurColors.hasBlur) blurColors.primaryTint else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(btnHeight)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.PlayArrow, 
                                                    contentDescription = null, 
                                                    modifier = Modifier.size(18.dp),
                                                    tint = playBtnTint
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    stringResource(R.string.search_play_all),
                                                    fontWeight = FontWeight.Bold,
                                                    color = playBtnTint
                                                )
                                            }
                                        }
                                    }
                                    val shuffleActiveTint = if (blurColors.hasBlur) {
                                        if (blurColors.useCustomControlsColor) Color.White else Color.Black
                                    } else {
                                        MaterialTheme.colorScheme.onPrimary
                                    }
                                    val shuffleInactiveTint = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.onSurface
                                    Surface(
                                        onClick = {
                                            if (isSearchActive) {
                                                pm.toggleShuffle()
                                                searchShuffle = pm.isShuffle
                                            } else {
                                                val newState = !searchShuffle
                                                searchShuffle = newState
                                                settings.setPlaylistShuffle(-300L, newState)
                                            }
                                        },
                                        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 26.dp, bottomEnd = 26.dp),
                                        color = if (searchShuffle) {
                                            if (blurColors.hasBlur) blurColors.primaryTint else MaterialTheme.colorScheme.primary
                                        } else {
                                            if (blurColors.hasBlur) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(btnHeight)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Shuffle, 
                                                    contentDescription = null, 
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (searchShuffle) shuffleActiveTint else shuffleInactiveTint
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    stringResource(R.string.search_shuffle),
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (searchShuffle) shuffleActiveTint else shuffleInactiveTint
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(searchResults.songs) { index, song ->
                        val isFirst = index == 0
                        val isLast = index == searchResults.songs.lastIndex
                        val isCurrent = song.id == currentlyPlayingId && activeCategory == "ALL"
                        SongItem(
                            isFirst = isFirst,
                            isLast = isLast,
                            song = song,
                            currentlyPlaying = isCurrent,
                            isPlaying = isPlaying && isCurrent,
                            hasBlurBackground = blurColors.hasBlur,
                            useCustomControlsColor = blurColors.useCustomControlsColor,
                            controlsColorPalette = blurColors.controlsColorPalette,
                            onClick = { onSongClick(song, allSongs, "ALL", -300L) },
                            onOptionsClick = { onOptionsClick(song) },
                            onFavoriteClick = onFavoriteClick
                        )
                    }
                }

                if (filterFavorites) searchResults.tagResults[sTabFavorites]?.let { songs ->
                    if (songs.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = sTabFavorites,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onNavigateToFolder("FAVORITES") }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .fillMaxWidth()
                            )
                        }
                        itemsIndexed(songs) { index, song ->
                            val isFirst = index == 0
                            val isLast = index == songs.lastIndex
                            val isCurrent = song.id == currentlyPlayingId && activeCategory == "FAVORITES"
                            SongItem(
                                isFirst = isFirst,
                                isLast = isLast,
                                song = song,
                                currentlyPlaying = isCurrent,
                                isPlaying = isPlaying && isCurrent,
                                hasBlurBackground = blurColors.hasBlur,
                                useCustomControlsColor = blurColors.useCustomControlsColor,
                                controlsColorPalette = blurColors.controlsColorPalette,
                                onClick = { onSongClick(song, searchResults.favoriteSongs, "FAVORITES", -200L) },
                                onOptionsClick = { onOptionsClick(song) },
                                onFavoriteClick = onFavoriteClick
                            )
                        }
                    } else {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                    headlineColor = blurColors.textColor,
                                    leadingIconColor = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary
                                ),
                                leadingContent = { 
                                    Icon(
                                        Icons.Default.Favorite, 
                                        contentDescription = null, 
                                        tint = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary
                                    ) 
                                },
                                modifier = Modifier.clickable { onNavigateToFolder("FAVORITES") }
                            ) {
                                Text(sTabFavorites, color = blurColors.textColor)
                            }
                        }
                    }
                }

                if (filterPlaylists && searchResults.playlistResults.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.playlists),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    val playlistList = searchResults.playlistResults.toList()
                    if (playlistList.isNotEmpty()) {
                        for ((playlist, matchingSongs) in playlistList) {
                            item {
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = blurColors.textColor
                                    ),
                                    leadingContent = {
                                        PlaylistPreviewCovers(playlistId = playlist.id, viewModel = viewModel, size = 50.dp)
                                    },
                                    modifier = Modifier.clickable { onNavigateToPlaylist(playlist) }
                                ) {
                                    Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = blurColors.textColor)
                                }
                            }
                            itemsIndexed(matchingSongs) { index, song ->
                                val isFirst = index == 0
                                val isLast = index == matchingSongs.lastIndex
                                val isCurrent = song.id == currentlyPlayingId && activeCategory == "PLAYLISTS" && activePlaylistId == playlist.id
                                SongItem(
                                    isFirst = isFirst,
                                    isLast = isLast,
                                    song = song,
                                    currentlyPlaying = isCurrent,
                                    isPlaying = isPlaying && isCurrent,
                                    hasBlurBackground = blurColors.hasBlur,
                                    useCustomControlsColor = blurColors.useCustomControlsColor,
                                    controlsColorPalette = blurColors.controlsColorPalette,
                                    modifier = Modifier.padding(start = 32.dp),
                                    onClick = { 
                                        val fullPlaylistSongs = playlistMappings.filter { it.playlistId == playlist.id }
                                            .mapNotNull { mapping -> allSongs.find { s -> s.id == mapping.songId } }
                                        onSongClick(song, fullPlaylistSongs, "PLAYLISTS", playlist.id) 
                                        onNavigateToPlaylist(playlist)
                                    },
                                    onOptionsClick = { onOptionsClick(song) },
                                    onFavoriteClick = onFavoriteClick
                                )
                            }
                        }
                    }
                }

                if (filterAlbums && searchResults.realAlbumResults.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = sTabAlbumsReal,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    val realAlbumList = searchResults.realAlbumResults.toList()
                    if (realAlbumList.isNotEmpty()) {
                        for ((album, matchingSongs) in realAlbumList) {
                            item {
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = blurColors.textColor,
                                        supportingColor = blurColors.textSecondaryColor
                                    ),
                                    supportingContent = { Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = blurColors.textSecondaryColor) },
                                    leadingContent = {
                                        AsyncImage(
                                            model = album.songs.firstOrNull()?.let { it.coverUrl ?: it.uri } ?: R.drawable.ic_launcher_foreground,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    },
                                    modifier = Modifier.clickable { onNavigateToAlbum(album) }
                                ) {
                                    Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = blurColors.textColor)
                                }
                            }
                            itemsIndexed(matchingSongs) { index, song ->
                                val isFirst = index == 0
                                val isLast = index == matchingSongs.lastIndex
                                val isCurrent = song.id == currentlyPlayingId && activeCategory == "ALBUMS" && activePlaylistId == album.id
                                SongItem(
                                    isFirst = isFirst,
                                    isLast = isLast,
                                    song = song,
                                    currentlyPlaying = isCurrent,
                                    isPlaying = isPlaying && isCurrent,
                                    hasBlurBackground = blurColors.hasBlur,
                                    useCustomControlsColor = blurColors.useCustomControlsColor,
                                    controlsColorPalette = blurColors.controlsColorPalette,
                                    modifier = Modifier.padding(start = 32.dp),
                                    onClick = { 
                                        onSongClick(song, album.songs, "ALBUMS", album.id)
                                        onNavigateToAlbum(album)
                                    },
                                    onOptionsClick = { onOptionsClick(song) },
                                    onFavoriteClick = onFavoriteClick
                                )
                            }
                        }
                    }
                }

                if (filterArtists && searchResults.albumResults.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tab_albums),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    val albumList = searchResults.albumResults.toList()
                    if (albumList.isNotEmpty()) {
                        for ((album, matchingSongs) in albumList) {
                            item {
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = blurColors.textColor,
                                        supportingColor = blurColors.textSecondaryColor
                                    ),
                                    supportingContent = { Text("${album.songs.size} canciones", color = blurColors.textSecondaryColor) },
                                    leadingContent = {
                                        AsyncImage(
                                            model = album.songs.firstOrNull()?.let { it.coverUrl ?: it.uri } ?: R.drawable.ic_launcher_foreground,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    },
                                    modifier = Modifier.clickable { onNavigateToAlbum(album) }
                                ) {
                                    Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = blurColors.textColor)
                                }
                            }
                            itemsIndexed(matchingSongs) { index, song ->
                                val isFirst = index == 0
                                val isLast = index == matchingSongs.lastIndex
                                val isCurrent = song.id == currentlyPlayingId && activeCategory == "ALBUMS" && activePlaylistId == album.id
                                SongItem(
                                    isFirst = isFirst,
                                    isLast = isLast,
                                    song = song,
                                    currentlyPlaying = isCurrent,
                                    isPlaying = isPlaying && isCurrent,
                                    hasBlurBackground = blurColors.hasBlur,
                                    useCustomControlsColor = blurColors.useCustomControlsColor,
                                    controlsColorPalette = blurColors.controlsColorPalette,
                                    modifier = Modifier.padding(start = 32.dp),
                                    onClick = { 
                                        onSongClick(song, album.songs, "ALBUMS", album.id)
                                        onNavigateToAlbum(album)
                                    },
                                    onOptionsClick = { onOptionsClick(song) },
                                    onFavoriteClick = onFavoriteClick
                                )
                            }
                        }
                    }
                }

                if (filterFolders) {
                    searchResults.tagResults.filterKeys { it != sTabFavorites }.forEach { (tagName, songs) ->
                        if (songs.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = tagName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { onNavigateToFolder(tagName) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .fillMaxWidth()
                                )
                            }
                            itemsIndexed(songs) { index, song ->
                                val isFirst = index == 0
                                val isLast = index == songs.lastIndex
                                val isCurrent = song.id == currentlyPlayingId && activeCategory == "FOLDERS"
                                SongItem(
                                    isFirst = isFirst,
                                    isLast = isLast,
                                    song = song,
                                    currentlyPlaying = isCurrent,
                                    isPlaying = isPlaying && isCurrent,
                                    hasBlurBackground = blurColors.hasBlur,
                                    useCustomControlsColor = blurColors.useCustomControlsColor,
                                    controlsColorPalette = blurColors.controlsColorPalette,
                                    onClick = {
                                        onSongClick(song, songs, "FOLDERS", tagName.hashCode().toLong())
                                        onNavigateToFolder(tagName)
                                    },
                                    onOptionsClick = { onOptionsClick(song) },
                                    onFavoriteClick = onFavoriteClick
                                )
                            }
                        } else {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = blurColors.textColor,
                                        leadingIconColor = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary
                                    ),
                                    leadingContent = { 
                                        Icon(
                                            Icons.Default.Folder, 
                                            contentDescription = null, 
                                            tint = if (blurColors.hasBlur) Color.White else MaterialTheme.colorScheme.primary
                                        ) 
                                    },
                                    modifier = Modifier.clickable { onNavigateToFolder(tagName) }
                                ) {
                                    Text(tagName, color = blurColors.textColor)
                                }
                            }
                        }
                    }
                }
                
                if (query.isNotBlank() && (!filterSongs || searchResults.songs.isEmpty()) && (!filterPlaylists || searchResults.playlistResults.isEmpty()) && (!filterAlbums || searchResults.realAlbumResults.isEmpty()) && (!filterArtists || searchResults.albumResults.isEmpty()) && (!filterFolders || searchResults.tagResults.filterKeys { it != sTabFavorites }.all { (_, songs) -> songs.isEmpty() })) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(text = "No results found", color = blurColors.textSecondaryColor)
                        }
                    }
                }
            }

            ScrollToTopPill(
                visible = isScrollToTopVisible.value,
                onClick = {
                    scope.launch {
                        listState.fastSmoothScrollToItem(0)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                hasBlurBackground = blurColors.hasBlur,
                isDarkTheme = blurColors.isDark
            )
        }
    }
}

    if (showFilterDialog) {
        val sectionCustomizationEnabled = settings.isSectionCustomizationEnabled
        val hiddenTabs = settings.hiddenSectionTabs
        val isAllDisabled = sectionCustomizationEnabled && "ALL" in hiddenTabs
        val isPlaylistsDisabled = sectionCustomizationEnabled && "PLAYLISTS" in hiddenTabs
        val isAlbumsDisabled = sectionCustomizationEnabled && "ALBUMS" in hiddenTabs
        val isArtistsDisabled = sectionCustomizationEnabled && "ALBUMS" in hiddenTabs
        val isFoldersDisabled = sectionCustomizationEnabled && "FOLDERS" in hiddenTabs

        val checkboxColors = CheckboxDefaults.colors(
            checkedColor = blurColors.primaryTint,
            checkmarkColor = if (blurColors.hasBlur) {
                if (blurColors.useCustomControlsColor) Color.White else Color.Black
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
        )
        Dialog(
            onDismissRequest = { showFilterDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AppBlurBackdrop(
                hasBlurBackground = blurColors.hasBlur,
                isDarkTheme = blurColors.isDark,
                currentSong = currentSong,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Surface(
                    color = if (blurColors.hasBlur) Color.Transparent else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = sFilterTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = blurColors.textColor
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isAllDisabled) { filterSongs = !filterSongs }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = filterSongs,
                                    enabled = !isAllDisabled,
                                    onCheckedChange = { if (!isAllDisabled) filterSongs = it },
                                    colors = checkboxColors
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sFilterAll,
                                    color = if (isAllDisabled) blurColors.textSecondaryColor.copy(alpha = 0.4f) else blurColors.textColor
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isPlaylistsDisabled) { filterPlaylists = !filterPlaylists }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = filterPlaylists,
                                    enabled = !isPlaylistsDisabled,
                                    onCheckedChange = { if (!isPlaylistsDisabled) filterPlaylists = it },
                                    colors = checkboxColors
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sFilterPlaylist,
                                    color = if (isPlaylistsDisabled) blurColors.textSecondaryColor.copy(alpha = 0.4f) else blurColors.textColor
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isAlbumsDisabled) { filterAlbums = !filterAlbums }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = filterAlbums,
                                    enabled = !isAlbumsDisabled,
                                    onCheckedChange = { if (!isAlbumsDisabled) filterAlbums = it },
                                    colors = checkboxColors
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sFilterAlbum,
                                    color = if (isAlbumsDisabled) blurColors.textSecondaryColor.copy(alpha = 0.4f) else blurColors.textColor
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isArtistsDisabled) { filterArtists = !filterArtists }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = filterArtists,
                                    enabled = !isArtistsDisabled,
                                    onCheckedChange = { if (!isArtistsDisabled) filterArtists = it },
                                    colors = checkboxColors
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sFilterArtist,
                                    color = if (isArtistsDisabled) blurColors.textSecondaryColor.copy(alpha = 0.4f) else blurColors.textColor
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isFoldersDisabled) { filterFolders = !filterFolders }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = filterFolders,
                                    enabled = !isFoldersDisabled,
                                    onCheckedChange = { if (!isFoldersDisabled) filterFolders = it },
                                    colors = checkboxColors
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sFilterFolder,
                                    color = if (isFoldersDisabled) blurColors.textSecondaryColor.copy(alpha = 0.4f) else blurColors.textColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showFilterDialog = false },
                                modifier = Modifier.bounceClick()
                            ) {
                                Text(sFilterCancel, color = blurColors.textSecondaryColor)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    filterSongs = true
                                    filterPlaylists = true
                                    filterAlbums = true
                                    filterArtists = true
                                    filterFolders = true
                                },
                                modifier = Modifier.bounceClick()
                            ) {
                                Text(sFilterAll, color = blurColors.primaryTint, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索模式：本地曲库 / 在线音源。 */
private enum class SearchMode { LOCAL, ONLINE }

/**
 * 在线搜索结果面板。
 *
 * 刻意复用 [SongItem]（本地列表用的同一个行组件），所以两个列表的视觉完全一致，
 * 不用再维护一套在线专用的行布局。
 */
@Composable
private fun OnlineSearchPanel(
    sources: List<String>,
    selectedSource: String?,
    onSelectSource: (String) -> Unit,
    results: List<Song>,
    favoriteSongs: List<Song>,
    loading: Boolean,
    searched: Boolean,
    query: String,
    hasBlur: Boolean,
    useCustomControlsColor: Boolean,
    controlsColorPalette: Int,
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean,
    currentUid: String?,
    onSongClick: (Song, List<Song>) -> Unit,
    onFavoriteSongClick: (Song) -> Unit,
    onOptionsClick: (Song) -> Unit,
    onFavoriteClick: ((Song) -> Unit)?,
) {
    if (sources.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "还没有可用的在线音源\n请先在「在线音源」页导入脚本并开启",
                color = secondaryColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { source ->
                FilterChip(
                    selected = source == selectedSource,
                    onClick = { onSelectSource(source) },
                    label = { Text(LxSources.displayName(source)) },
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }

            results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results) { index, song ->
                    val isCurrent = song.uid == currentUid
                    SongItem(
                        song = song,
                        currentlyPlaying = isCurrent,
                        isPlaying = isPlaying && isCurrent,
                        isFirst = index == 0,
                        isLast = index == results.lastIndex,
                        hasBlurBackground = hasBlur,
                        useCustomControlsColor = useCustomControlsColor,
                        controlsColorPalette = controlsColorPalette,
                        onClick = { onSongClick(song, results) },
                        onOptionsClick = { onOptionsClick(song) },
                        onFavoriteClick = onFavoriteClick,
                    )
                }
            }

            // 没输入关键词时展示「收藏的在线歌」，否则这些歌没有地方可以找到
            query.isBlank() && favoriteSongs.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "收藏的在线歌曲",
                        style = MaterialTheme.typography.titleSmall,
                        color = secondaryColor,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(favoriteSongs) { index, song ->
                    val isCurrent = song.uid == currentUid
                    SongItem(
                        song = song,
                        currentlyPlaying = isCurrent,
                        isPlaying = isPlaying && isCurrent,
                        isFirst = index == 0,
                        isLast = index == favoriteSongs.lastIndex,
                        hasBlurBackground = hasBlur,
                        useCustomControlsColor = useCustomControlsColor,
                        controlsColorPalette = controlsColorPalette,
                        onClick = { onFavoriteSongClick(song) },
                        onOptionsClick = { onOptionsClick(song) },
                        onFavoriteClick = onFavoriteClick,
                    )
                }
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        query.isBlank() -> "输入关键词搜索在线音乐"
                        searched -> "没有找到结果"
                        else -> "搜索中…"
                    },
                    color = secondaryColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
