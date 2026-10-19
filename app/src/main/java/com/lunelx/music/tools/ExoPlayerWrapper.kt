package com.lunelx.music.tools

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * ExoPlayer 的 MediaPlayer 形状适配层。
 *
 * ## 为什么要这一层
 * Lune 原实现把 `android.media.MediaPlayer` 的调用散落在 MusicService 里
 * （约 50 处）。直接换成 ExoPlayer 需要改动每一处，风险高、回归面大。
 *
 * 这一层把 ExoPlayer 包装成 MediaPlayer 的常用子集（start/pause/seekTo/
 * setVolume/isPlaying/currentPosition/duration/audioSessionId 等），
 * 让 MusicService 的既有逻辑几乎原样保留。
 *
 * ## 与 MediaPlayer 的行为差异（已在实现中抹平）
 * - MediaPlayer 的 `setDataSource` 是同步的，`start()` 前需要 `prepare()`；
 *   这里 `setDataSource` 只记录，`start()` 时 `prepare()+play()`。
 * - MediaPlayer 的 `duration` 在 prepare 前是 -1；这里在准备好前返回 0。
 * - `isLooping` 映射到 `repeatMode`。
 *
 * ## 音频会话
 * 音效链（EQ / BassBoost / Virtualizer / Reverb / Dynamics / Loudness）
 * 全部依赖 `audioSessionId`。这里在构造时生成一个固定 sessionId 并交给
 * ExoPlayer，因此 [com.lunelx.music.audio] 下的所有音效代码无需修改。
 */
@OptIn(UnstableApi::class)
class ExoPlayerWrapper(
    private val context: Context,
    private val onCompleted: () -> Unit,
    private val onError: (String) -> Unit,
    private val onPrepared: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val player: ExoPlayer

    /** 供音效链使用的会话 ID。ExoPlayer 建好后即为该值。 */
    val audioSessionId: Int

    /** 当前是否已装载数据源。 */
    private var hasSource = false

    /** 上一次观察到的播放状态，用于识别"播完"。 */
    private var lastState: Int = Player.STATE_IDLE

    /**
     * 本次数据源是否已经回调过 [onPrepared]。
     *
     * MediaPlayer 的 `onPrepared` 每次 `setDataSource + prepare` 只触发一次；
     * 而 ExoPlayer 的 `STATE_READY` 在每次缓冲结束（`BUFFERING→READY`）和播完后拖动
     * （`ENDED→READY`）时都会再次到达。调用方（MusicService）把它们当一次性的用：
     * 在回调里 `start()`、重建整条音效链、`seekTo(保存的进度)`。若重复触发，就会出现
     * 「用户已暂停却自己播起来」「队列播完后最后一首又从 0:00 重放」
     * 「恢复播放的歌每次卡顿都跳回原来的进度」。用这个标志把语义对齐。
     */
    private var preparedOnce = false

    /** 循环播放。 */
    var isLooping: Boolean
        get() = player.repeatMode == Player.REPEAT_MODE_ONE
        set(value) {
            player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    /** 当前是否正在播放。 */
    val isPlaying: Boolean
        get() = player.isPlaying

    /** 当前播放位置（毫秒）。 */
    val currentPosition: Int
        get() = player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** 总时长（毫秒）。未准备好时为 0。 */
    val duration: Int
        get() {
            val d = player.duration
            return if (d == C.TIME_UNSET || d < 0) 0 else d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    /** 是否已准备好可播放。 */
    val isPrepared: Boolean
        get() = player.playbackState == Player.STATE_READY

    init {
        val sessionId = generateSessionId(context)
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
        audioSessionId = sessionId
        runCatching { player.setAudioSessionId(sessionId) }
            .onFailure { Log.w(TAG, "setAudioSessionId failed: ${it.message}") }
        Log.i(TAG, "ExoPlayer created, audioSessionId=$sessionId (must be > 0)")

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when {
                    state == Player.STATE_READY && lastState != Player.STATE_READY -> {
                        // 只在本次数据源首次就绪时回调，见 preparedOnce 的说明
                        if (!preparedOnce) {
                            preparedOnce = true
                            mainHandler.post { onPrepared() }
                        }
                    }
                    state == Player.STATE_ENDED -> {
                        // 循环模式下 ExoPlayer 自己处理，不回调完成
                        if (player.repeatMode != Player.REPEAT_MODE_ONE) {
                            mainHandler.post { onCompleted() }
                        }
                    }
                }
                lastState = state
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "player error: ${error.errorCodeName} ${error.message}")
                mainHandler.post { onError(error.message ?: error.errorCodeName) }
            }
        })
    }

    /**
     * 装载数据源。等价于 MediaPlayer.setDataSource。
     * 支持 `content://`、`file://`、`http(s)://`，以及本项目的流代理地址。
     */
    fun setDataSource(uri: Uri) {
        val item = MediaItem.fromUri(uri)
        player.setMediaItem(item)
        // 必须在 prepare() 之前确保会话已绑定：ExoPlayer 在首次 prepare 时
        // 创建底层 AudioTrack，此时才会真正应用 audioSessionId。
        // 若会话无效，音效会挂到全局混音，产生持续的滋滋声。
        runCatching { player.setAudioSessionId(audioSessionId) }
            .onFailure { Log.w(TAG, "re-apply audioSessionId failed: ${it.message}") }
        hasSource = true
        lastState = Player.STATE_IDLE
        preparedOnce = false
    }

    /** 装载本地文件。 */
    fun setDataSource(path: String) = setDataSource(Uri.parse(path))

    /**
     * 只准备、不出声。
     *
     * 恢复上次进度时必须走这里：`prepare()` 才会进入 READY 并触发 [onPrepared]
     * （那里负责 seek 到保存的位置、刷新通知与时长），但 `playWhenReady` 保持 false，
     * 所以不会自己响。之前 restorePlayback 直接调 [start]，导致一打开 App 就自动播放。
     */
    fun prepare() {
        if (!hasSource) return
        if (!isPrepared) player.prepare()
    }

    /**
     * 开始播放。若尚未 prepare 会先 prepare。
     * 对应 MediaPlayer 的 start()。
     */
    fun start() {
        if (!hasSource) return
        if (!isPrepared) player.prepare()
        player.play()
    }

    /** 暂停。 */
    fun pause() {
        player.pause()
    }

    /** 停止并释放媒体资源，但保留播放器实例可复用。 */
    fun stop() {
        player.stop()
        hasSource = false
        lastState = Player.STATE_IDLE
    }

    /** 跳转。 */
    fun seekTo(positionMs: Int) {
        player.seekTo(positionMs.toLong().coerceAtLeast(0))
    }

    /** 设置音量，0f..1f。 */
    fun setVolume(left: Float, right: Float) {
        // ExoPlayer 只有单声道音量，左右平衡由 BalanceEffect 在音效层完成
        player.volume = ((left + right) / 2f).coerceIn(0f, 1f)
    }

    /** 彻底释放。释放后不可再用。 */
    fun release() {
        runCatching { player.stop() }
        runCatching { player.release() }
        hasSource = false
    }

    /** 暴露底层实例，供需要 media3 原生 API 的地方（如 MediaSession）使用。 */
    fun raw(): ExoPlayer = player

    companion object {
        private const val TAG = "ExoPlayerWrapper"

        /**
         * 生成一个独立的音频会话 ID。
         *
         * **这一项至关重要**：所有音效（EQ / BassBoost / Virtualizer /
         * LoudnessEnhancer / DynamicsProcessing / Reverb）都以 sessionId 为锚点。
         * 若拿不到有效 sessionId（返回 0），这些音效会挂到**全局输出混音**
         * （output mix 0）上，于是整个系统的声音都被增益与压缩，
         * 表现为持续「滋滋」电流声。必须确保拿到非 0 的真实会话。
         *
         * 三级回退：
         * 1. `AudioManager.generateAudioSessionId()`（公开 API，API 21+）
         * 2. `AudioManager.generateAudioSessionId()` 反射（兼容老实现）
         * 3. 自增伪随机（仍避开 0，宁可单调也不污染全局混音）
         */
        private fun generateSessionId(context: Context): Int {
            // 1. 公开 API
            runCatching {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val id = am.generateAudioSessionId()
                if (id > 0) return id
            }

            // 2. 反射兜底（实例方法，必须在实例上调用）
            runCatching {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val m = android.media.AudioManager::class.java
                    .getMethod("generateAudioSessionId")
                val id = m.invoke(am) as Int
                if (id > 0) return id
            }

            // 3. 绝不返回 0：0 表示全局混音器
            Log.w(TAG, "generateAudioSessionId failed, falling back to pseudo id")
            return (System.nanoTime() and 0x7FFFFFFF).toInt().let {
                if (it <= 0) 1 else it
            }
        }
    }
}
