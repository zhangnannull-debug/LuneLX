package com.lunelx.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.lunelx.music.lx.LxRepository
import com.lunelx.music.tools.AudioThumbnailFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LuneApplication : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 「迷你播放器被收成一颗粒子」的状态**不跨进程保留**。
        //
        // 这个标记原本是持久化的：用户（或误触）把播放胶囊收起一次，之后每次重启 App
        // 都停在"没有播放胶囊、但音乐在响"的状态 —— 现场看起来就是"播放胶囊没了"。
        // 收起只是临时视图偏好，冷启动应当回到默认的胶囊形态。
        runCatching {
            com.lunelx.music.tools.SettingsManager.getInstance(this).isMiniPlayerMinimized = false
        }

        // 预热 LX 音源子系统：恢复已导入脚本并加载其能力声明。
        // 放到后台线程，不阻塞首屏。
        appScope.launch {
            runCatching { LxRepository.getInstance(this@LuneApplication).refreshScripts() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(AudioThumbnailFetcher.Factory(this@LuneApplication))
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
