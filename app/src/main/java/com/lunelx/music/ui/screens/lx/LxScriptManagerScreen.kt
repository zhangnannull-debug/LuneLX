package com.lunelx.music.ui.screens.lx

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.lunelx.music.lx.LxRepository
import com.lunelx.music.tools.PlaybackManager
import com.lunelx.music.ui.components.AppBlurBackdrop
import com.lunelx.music.ui.components.rememberBlurSheetColors
import com.lunelx.music.lx.engine.LxSources
import com.lunelx.music.lx.engine.LxScriptMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音源脚本管理页。
 *
 * 支持两种导入方式：
 * - 从本地 `.js` 文件导入（系统文件选择器）
 * - 从 URL 导入（直接拉取脚本文本）
 *
 * 脚本存放在应用私有目录，无需任何存储权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LxScriptManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { LxRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    val scripts by repo.scripts.collectAsState()
    val capabilities by repo.capabilities.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<LxScriptMeta?>(null) }
    val blurColors = rememberBlurSheetColors(PlaybackManager.getInstance(context).currentSong)

    LaunchedEffect(Unit) { repo.refreshScripts() }
    BackHandler { onBack() }

    // 本地文件选择
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                message = "读取文件失败"
            } else {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "script.js"
                val result = repo.importScript(name, content)
                message = result.fold(
                    onSuccess = { "导入成功：${it.sources.keys.joinToString { s -> LxSources.displayName(s) }}" },
                    onFailure = { "导入失败：${it.message}" },
                )
            }
            busy = false
        }
    }

    AppBlurBackdrop(
        hasBlurBackground = blurColors.hasBlur,
        isDarkTheme = blurColors.isDark,
        currentSong = PlaybackManager.getInstance(context).currentSong,
        modifier = Modifier.fillMaxSize(),
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("音源脚本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch(arrayOf("application/javascript", "text/plain", "*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入脚本") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "在线音源",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = repo.onlineEnabled,
                    onCheckedChange = { repo.onlineEnabled = it },
                )
            }
            Text(
                text = "关闭后隐藏在线入口，仅播放本地音乐。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = { showUrlDialog = true }) {
                Text("从 URL 导入")
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            if (scripts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有导入脚本",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(scripts, key = { it.assetPath }) { meta ->
                        ScriptCard(
                            meta = meta,
                            supportedSources = capabilities[meta.assetPath]
                                ?.sources?.keys?.map { LxSources.displayName(it) }
                                ?: emptyList(),
                            onToggle = { enabled ->
                                scope.launch { repo.setScriptEnabled(meta.assetPath, enabled) }
                            },
                            onDelete = { pendingDelete = meta },
                        )
                    }
                }
            }
        }
    }

    // URL 导入对话框
    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("从 URL 导入脚本") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://example.com/source.js") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "只从你信任的来源导入。脚本可以访问网络。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlInput.startsWith("http") && !busy,
                    onClick = {
                        val url = urlInput.trim()
                        showUrlDialog = false
                        scope.launch {
                            busy = true
                            message = null
                            val content = withContext(Dispatchers.IO) { fetchUrl(url) }
                            if (content.isNullOrBlank()) {
                                message = "下载失败，请检查链接"
                            } else {
                                val name = url.substringAfterLast('/').ifBlank { "script.js" }
                                val result = repo.importScript(name, content)
                                message = result.fold(
                                    onSuccess = { "导入成功" },
                                    onFailure = { "导入失败：${it.message}" },
                                )
                            }
                            busy = false
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
            },
        )
    }

    // 删除确认
    pendingDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除脚本") },
            text = { Text("确定删除「${meta.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    repo.removeScript(meta.assetPath)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
    }
}

@Composable
private fun ScriptCard(
    meta: LxScriptMeta,
    supportedSources: List<String>,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var enabled by remember(meta.assetPath) { mutableStateOf(true) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.version.isNotBlank()) {
                    Text(
                        text = "v${meta.version}" + if (meta.author.isNotBlank()) " · ${meta.author}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (supportedSources.isNotEmpty()) {
                    Text(
                        text = "支持：" + supportedSources.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; onToggle(it) })
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 拉取 URL 文本，限制大小防止误导入超大文件。 */
private fun fetchUrl(url: String): String? = runCatching {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
    try {
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.bufferedReader().use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
                if (sb.length > MAX_SCRIPT_BYTES) return null
            }
            sb.toString()
        }
    } finally {
        conn.disconnect()
    }
}.getOrNull()

private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
