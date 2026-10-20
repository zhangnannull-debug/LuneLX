package com.lunelx.music.ui.screens.online

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lunelx.music.lx.LxRepository
import com.lunelx.music.lx.discover.LxDiscoverBoard
import com.lunelx.music.lx.engine.LxSources
import com.lunelx.music.tools.PlaybackManager
import com.lunelx.music.tools.Song
import com.lunelx.music.ui.components.HeaderSurface
import com.lunelx.music.ui.components.SongItem
import com.lunelx.music.ui.utils.bounceClick

/**
 * 「在线音乐」分区页。
 *
 * 结构（自上而下）：
 * 1. **折叠收纳区**：标题行 + 展开/收起；展开后里面是五个平台的标签
 *    （酷狗音乐 / QQ音乐 / 网易云音乐 / 酷我音乐 / 咪咕音乐）。
 *    收起后只留一行，把竖向空间让给内容。
 * 2. **内容**：当前平台的「发现」（各平台官方榜单）→ 点开某个榜 → 榜单歌曲列表。
 *
 * 数据来自 [LxRepository.discoverBoards] / [LxRepository.discoverBoardSongs]（内置平台接口），
 * 播放仍然走音源脚本的 `musicUrl`，所以「能看」不依赖脚本，「能播」才依赖。
 */
@Composable
fun OnlineMusicScreen(
    bottomPadding: Dp,
    hasBlurBackground: Boolean,
    isDarkTheme: Boolean,
    useCustomControlsColor: Boolean,
    controlsColorPalette: Int,
    onSongClick: (Song, List<Song>) -> Unit,
    onOptionsClick: (Song) -> Unit,
    onFavoriteClick: ((Song) -> Unit)? = null,
    onOpenSearch: () -> Unit = {},
    /**
     * 平台标签行是否显示 —— 直接用顶栏那个「分区标题」开关（`showSectionHeaders`）控制，
     * 与「全部」等页面的头部行为完全一致：收起时只剩列表。
     */
    showHeader: Boolean = true,
    /**
     * 当前打开的榜单（null = 停在榜单列表）。
     *
     * 状态由调用方（Lune）持有：系统返回键要在「榜单歌曲列表 → 榜单列表」之间退，
     * 而 Activity 级的 BackHandler 比页面内部的注册得更晚、优先级更高，
     * 所以把状态提上去、由父级注册返回处理才可靠。
     */
    openedBoard: LxDiscoverBoard? = null,
    onOpenedBoardChange: (LxDiscoverBoard?) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { LxRepository.getInstance(context) }
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    var selectedSource by rememberSaveable { mutableStateOf(LxSources.KUGOU) }

    var boards by remember { mutableStateOf<List<LxDiscoverBoard>>(emptyList()) }
    var boardsLoading by remember { mutableStateOf(false) }
    var boardsError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var songsLoading by remember { mutableStateOf(false) }
    // 榜单歌曲的重试计数：openedBoard 现在由父级持有，"先置空再设回" 不会触发重组，
    // 所以重试必须靠一个独立的 tick。
    var songsReloadTick by remember { mutableIntStateOf(0) }

    val onlineEnabled = repo.onlineEnabled
    val discoverSupported = com.lunelx.music.lx.discover.LxDiscover.isSupported(selectedSource)

    // 平台的「发现」榜单
    LaunchedEffect(selectedSource, reloadTick, onlineEnabled) {
        onOpenedBoardChange(null)
        songs = emptyList()
        boardsError = null
        boards = emptyList()
        if (!onlineEnabled) return@LaunchedEffect
        if (!com.lunelx.music.lx.discover.LxDiscover.isSupported(selectedSource)) return@LaunchedEffect
        boardsLoading = true
        boards = runCatching { repo.discoverBoards(selectedSource) }
            .onFailure { boardsError = it.message ?: "加载失败" }
            .getOrDefault(emptyList())
        boardsLoading = false
    }

    // 打开某个榜 → 拉歌曲
    LaunchedEffect(openedBoard, songsReloadTick) {
        val board = openedBoard ?: return@LaunchedEffect
        songsLoading = true
        songs = emptyList()
        songs = runCatching { repo.discoverBoardSongs(board.source, board.id) }
            .onFailure { boardsError = it.message }
            .getOrDefault(emptyList())
        songsLoading = false
    }

    val secondaryColor = if (hasBlurBackground && isDarkTheme) Color.White.copy(alpha = 0.72f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    // ⚠️ 本页没有挂在 Surface 里，Material3 的 LocalContentColor 默认是**黑色**，
    // 深色模式下不显式给颜色的话，榜单标题和返回箭头就是黑底黑字/黑箭头。
    val textColor = when {
        hasBlurBackground && isDarkTheme -> Color.White
        hasBlurBackground -> Color(0xFF141316)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------------- 平台标签（顶部收纳行） ----------------
        // 位置与「全部」页的头部动作行一致：紧贴顶栏下方的整宽 HeaderSurface。
        // 展开/收起由顶栏那个「分区标题」按钮统一控制（和别的页面一样），
        // 收起后这里整行消失，只剩下面的列表。
        AnimatedVisibility(
            visible = showHeader,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            HeaderSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                hasBlurBackground = hasBlurBackground,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformTabs.forEach { (source, label) ->
                        FilterChip(
                            selected = source == selectedSource,
                            onClick = { selectedSource = source },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // ---------------- 内容 ----------------
        val board = openedBoard
        if (board != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = { onOpenedBoardChange(null) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回榜单",
                        tint = textColor,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = board.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (songsLoading) "加载中…" else "${songs.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                    )
                }
            }

            when {
                songsLoading -> LoadingBox()
                songs.isEmpty() -> EmptyHint(
                    text = "这个榜单没有取到歌曲\n可能是平台接口临时不可用",
                    secondaryColor = secondaryColor,
                    onRetry = { songsReloadTick++ },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding),
                ) {
                    itemsIndexed(songs, key = { _, s -> s.uid }) { index, song ->
                        val isCurrent = playbackManager.currentSong?.uid == song.uid
                        SongItem(
                            song = song,
                            currentlyPlaying = isCurrent,
                            isPlaying = isCurrent && playbackManager.isPlaying,
                            isFirst = index == 0,
                            isLast = index == songs.lastIndex,
                            hasBlurBackground = hasBlurBackground,
                            useCustomControlsColor = useCustomControlsColor,
                            controlsColorPalette = controlsColorPalette,
                            onClick = { onSongClick(song, songs) },
                            onOptionsClick = { onOptionsClick(song) },
                            onFavoriteClick = onFavoriteClick,
                        )
                    }
                }
            }
            return@Column
        }

        when {
            !onlineEnabled -> EmptyHint(
                text = "在线音源未开启\n去 设置 → 音源 打开，并导入音源脚本",
                secondaryColor = secondaryColor,
                onRetry = onOpenSearch,
                retryLabel = "去搜索",
            )

            !discoverSupported -> EmptyHint(
                text = "该平台暂未提供可用的发现数据\n（酷我 / 咪咕的榜单需要登录态，匿名接口已被关闭）\n换一个平台看看，或者直接用搜索",
                secondaryColor = secondaryColor,
                onRetry = onOpenSearch,
                retryLabel = "去搜索",
            )

            boardsLoading -> LoadingBox()

            boards.isEmpty() -> EmptyHint(
                text = boardsError?.let { "榜单加载失败：$it" } ?: "没有取到榜单数据",
                secondaryColor = secondaryColor,
                onRetry = { reloadTick++ },
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(boards, key = { it.source + it.id }) { item ->
                    BoardCard(
                        board = item,
                        textColor = textColor,
                        secondaryColor = secondaryColor,
                        onClick = { onOpenedBoardChange(item) },
                    )
                }
            }
        }
    }
}

/** 五个平台的标签（顺序按用户给的：酷狗 / QQ / 网易云 / 酷我 / 咪咕）。 */
private val PlatformTabs: List<Pair<String, String>> = listOf(
    LxSources.KUGOU to "酷狗音乐",
    LxSources.TENCENT to "QQ音乐",
    LxSources.NETEASE to "网易云音乐",
    LxSources.KUWO to "酷我音乐",
    LxSources.MIGU to "咪咕音乐",
)

@Composable
private fun BoardCard(
    board: LxDiscoverBoard,
    textColor: Color,
    secondaryColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = board.cover,
                    contentDescription = board.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = board.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!board.subtitle.isNullOrBlank()) {
                    Text(
                        text = board.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

@Composable
private fun EmptyHint(
    text: String,
    secondaryColor: Color,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "重试",
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = secondaryColor,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRetry) { Text(retryLabel) }
            }
        }
    }
}
