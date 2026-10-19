@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.lunelx.music.tools

import android.app.*
import android.content.*
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.content.ContentUris
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import com.lunelx.music.data.MusicDatabase
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lunelx.music.R
import com.lunelx.music.audio.BalanceEffect
import com.lunelx.music.audio.DynamicsEffect
import com.lunelx.music.audio.LoudnessEffect
import com.lunelx.music.audio.ReverbEffect
import com.lunelx.music.ui.activities.Lune
import kotlinx.coroutines.*
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.regex.Pattern
import android.util.Log
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.widget.RemoteViews
import android.media.AudioDeviceInfo
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class MusicService : MediaLibraryService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        ensureMediaSession()
        return mediaSession
    }

    private var lunePlayerAdapter: LuneAudioPlayerAdapter? = null
    private var mediaPlayer: ExoPlayerWrapper? = null
    private var secondaryPlayer: ExoPlayerWrapper? = null
    private var isCrossfading = false
    private var mediaSession: MediaLibrarySession? = null
    private val binder = MusicBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    internal var equalizer: Equalizer? = null
    internal var bassBoost: BassBoost? = null
    internal var virtualizer: Virtualizer? = null

    internal var loudnessEffect: LoudnessEffect? = null
    internal var reverbEffect: ReverbEffect? = null
    internal var dynamicsEffect: DynamicsEffect? = null

    private var secondaryEqualizer: Equalizer? = null
    private var secondaryBassBoost: BassBoost? = null
    private var secondaryVirtualizer: Virtualizer? = null

    private lateinit var settingsManager: SettingsManager
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false
    private var wasPlayingBeforeLoss = false
    private var isDucked = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val playbackAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private fun acquireWakeLock(timeoutMs: Long = 0L) {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lune:MusicPlaybackWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    if (timeoutMs > 0) {
                        wl.acquire(timeoutMs)
                    } else {
                        wl.acquire()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MusicService", "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                }
            }
        } catch (e: Exception) {
            Log.w("MusicService", "Error releasing wake lock: ${e.message}")
        }
    }

    /**
     * ExoPlayer 的音频属性与唤醒锁在 [ExoPlayerWrapper] 内部处理，
     * 这里保留空实现以兼容既有调用点。
     */
    private fun configurePlayer(player: ExoPlayerWrapper) {
    }

    private var effectTransitionJob: Job? = null
    private var widgetUpdateJob: Job? = null
    private var spatialRampJob: Job? = null
    private var currentSpatialStrength: Short = 0
    private var lastSongForBlur: Song? = null

    /**
     * 服务侧「当前正在播放的歌」的副本。
     *
     * [PlaybackManager] 才是权威状态，但这个进程里存在一个真实的窗口：
     * Activity 重建 / 进程被回收后回来时，服务可能还在放，而 PlaybackManager 是空的。
     * 那时候 UI 会表现为「底部没有播放胶囊，但音乐还在响」。用它把状态认领回来。
     * 在 [setPlayerDataSource] 里赋值 —— 那是所有换歌路径的唯一入口。
     */
    var lastPlayedSong: Song? = null
        private set
    private var lastBlurredBitmap: Bitmap? = null
    private var lastSongForRounded: Song? = null
    private var cachedRoundedArt: Bitmap? = null
    private var currentNotificationArt: Bitmap? = null

    /**
     * 当前通知封面属于哪首歌（uid）。
     *
     * 以前是 Long 的 `song.id`，而在线歌 id 恒为 0 → 判断「封面是不是这首歌的」
     * 永远为真，B 歌会沿用 A 歌的封面。
     */
    private var currentNotificationArtSongUid: String? = null
    private var becomingNoisyReceiver: BroadcastReceiver? = null

    internal fun getCurrentTargetVolumes(): Pair<Float, Float> {
        val balance = PlaybackManager.getInstance(applicationContext).balance
        val (left, right) = BalanceEffect.volumesForBalance(balance)
        val duckFactor = if (isDucked) 0.2f else 1.0f
        return (left * duckFactor) to (right * duckFactor)
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent focus loss (another app claimed audio): pause
                hasAudioFocus = false
                resumeOnFocusGain = false
                wasPlayingBeforeLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Transient loss (incoming call, etc): pause, try to resume later
                hasAudioFocus = false
                wasPlayingBeforeLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Notification/brief sound: lower volume
                isDucked = true
                val (left, right) = getCurrentTargetVolumes()
                mediaPlayer?.setVolume(left, right)
                secondaryPlayer?.setVolume(left, right)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus: restore volume and resume if we were playing or waiting for delayed focus
                hasAudioFocus = true
                isDucked = false
                val (left, right) = getCurrentTargetVolumes()
                mediaPlayer?.setVolume(left, right)
                secondaryPlayer?.setVolume(left, right)
                if (resumeOnFocusGain || wasPlayingBeforeLoss) {
                    resumeOnFocusGain = false
                    wasPlayingBeforeLoss = false
                    resumeInternal()
                }
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "com.lunelx.music.ACTION_PLAY"
        const val ACTION_PAUSE = "com.lunelx.music.ACTION_PAUSE"
        const val ACTION_PREVIOUS = "com.lunelx.music.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.lunelx.music.ACTION_NEXT"
        const val ACTION_SHUFFLE = "com.lunelx.music.ACTION_SHUFFLE"
        const val ACTION_FAVORITE = "com.lunelx.music.ACTION_FAVORITE"
        const val ACTION_DISMISS = "com.lunelx.music.ACTION_DISMISS"
        const val ACTION_UPDATE_WIDGET = "com.lunelx.music.ACTION_UPDATE_WIDGET"
        const val PAUSE_TIMEOUT_MS = 5 * 60 * 1000L
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private var pauseTimeoutJob: Job? = null
    private var isNotificationDismissed = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        val channelId = "music_playback_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Music Playback", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }


        becomingNoisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying()) {
                    PlaybackManager.getInstance(applicationContext).pause()
                }
            }
        }
        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        ensureMediaSession()

        settingsManager = SettingsManager.getInstance(this)

        serviceScope.launch {
            PlaybackManager.getInstance(this@MusicService).refreshNotification.collect {
                val pm = PlaybackManager.getInstance(this@MusicService)
                val song = pm.currentSong
                if (song != null) {
                    showNotification(song, isPlaying())
                }
                updatePlaybackState()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAudioAttributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .build().also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasAudioFocus = true
                resumeOnFocusGain = false
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                hasAudioFocus = false
                resumeOnFocusGain = true
                false
            }
            else -> {
                hasAudioFocus = false
                resumeOnFocusGain = false
                false
            }
        }
    }

    private fun abandonAudioFocus() {
        hasAudioFocus = false
        resumeOnFocusGain = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    /**
     * 挂载音效链。
     *
     * 安全护栏：sessionId 为 0 或负数时**直接跳过**。
     * 0 代表全局输出混音（output mix 0），把 EQ / 压缩 / 增益挂上去
     * 会污染整个系统的音频输出，听感上就是持续的滋滋电流声。
     * 宁可这首不放音效，也不能影响全局。
     */
    private fun setupAudioFx(sessionId: Int, isSecondary: Boolean = false) {
        if (sessionId <= 0) {
            Log.w("MusicService", "setupAudioFx skipped: invalid sessionId=$sessionId")
            return
        }
        if (isSecondary) {
            secondaryEqualizer?.release()
            secondaryBassBoost?.release()
            secondaryVirtualizer?.release()
            loudnessEffect?.release(true)
            reverbEffect?.release(true)
            dynamicsEffect?.release(true)
        } else {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            loudnessEffect?.release(false)
            reverbEffect?.release(false)
            dynamicsEffect?.release(false)
        }

        try {
            val eq = Equalizer(0, sessionId).apply {
                val storedBands = settingsManager.eqBandLevels.split(",").filter { it.isNotEmpty() }
                if (storedBands.size == numberOfBands.toInt()) {
                    for (i in 0 until numberOfBands) {
                        try {
                            setBandLevel(i.toShort(), storedBands[i].toShort())
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                enabled = settingsManager.isEqEnabled
            }
            if (isSecondary) secondaryEqualizer = eq else equalizer = eq
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val bb = BassBoost(0, sessionId).apply {
                if (strengthSupported) setStrength(settingsManager.bassBoostLevel.toShort())
                enabled = settingsManager.isBassBoostEnabled
            }
            if (isSecondary) secondaryBassBoost = bb else bassBoost = bb
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val virt = Virtualizer(0, sessionId).apply {
                enabled = settingsManager.isSpatialAudioEnabled
                if (strengthSupported) {
                    setStrength(800.toShort())
                    currentSpatialStrength = 800.toShort()
                }
            }
            if (isSecondary) secondaryVirtualizer = virt else virtualizer = virt
        } catch (e: Exception) { e.printStackTrace() }

        try {
            if (isSecondary) {
                loudnessEffect?.setup(sessionId, true, settingsManager.isLoudnessEnabled, settingsManager.loudnessGain)
                reverbEffect?.setup(sessionId, true, settingsManager.reverbPreset)
                dynamicsEffect?.setup(sessionId, true, settingsManager.dynamicsPreset)
            } else {
                val loud = LoudnessEffect().apply {
                    setup(sessionId, false, settingsManager.isLoudnessEnabled, settingsManager.loudnessGain)
                }
                loudnessEffect = loud
                val rev = ReverbEffect().apply {
                    setup(sessionId, false, settingsManager.reverbPreset)
                }
                reverbEffect = rev
                val dyn = DynamicsEffect().apply {
                    setup(sessionId, false, settingsManager.dynamicsPreset)
                }
                dynamicsEffect = dyn
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val pm = PlaybackManager.getInstance(this)

        when (action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> pm.playPreviousFromService()
            ACTION_NEXT -> pm.playNextFromService()
            ACTION_SHUFFLE -> {
                pm.toggleShuffle()
                currentSong()?.let { showNotification(it, isPlaying()) }
                updatePlaybackState()
            }
            ACTION_FAVORITE -> {
                pm.toggleFavorite(onFavoriteToggled = { updatedSong ->
                    val provider = MusicProvider(this)
                    provider.updateSongInCache(updatedSong)
                    showNotification(updatedSong, isPlaying())
                })
            }
            ACTION_DISMISS -> {
                isNotificationDismissed = true
                pauseTimeoutJob?.cancel()
                try {
                    mediaPlayer?.pause()
                    secondaryPlayer?.pause()
                } catch (_: Exception) {}
                val pm = PlaybackManager.getInstance(applicationContext)
                pm.updatePlayingState(false)
                pm.savePlaybackState(wasPlaying = false)
                stopWidgetUpdateTimer()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                try {
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager?.cancel(1)
                } catch (_: Exception) {}
                updatePlaybackState()
                stopSelf()
            }
            ACTION_UPDATE_WIDGET -> {
                val pm = PlaybackManager.getInstance(this)
                if (pm.currentSong == null && !pm.stateRestored) {
                    pm.restorePlaybackState()
                }
                lastSongForRounded = null
                lastSongForBlur = null
                cachedRoundedArt = null
                lastBlurredBitmap = null
                updateWidget()
            }
            else -> {
                val currentOrPending = currentSong() ?: pm.currentSong ?: pm.pendingPlaySong
                if (currentOrPending != null && isPlaying()) {
                    showNotification(currentOrPending, true)
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val shouldStopOnClose = settingsManager.stopOnTaskRemoved
        val playing = isPlaying()
        Log.i("MusicService", "onTaskRemoved triggered: shouldStopOnClose=$shouldStopOnClose, isPlaying=$playing")
        if (shouldStopOnClose) {
            isNotificationDismissed = true
            pauseTimeoutJob?.cancel()
            try {
                mediaPlayer?.pause()
                secondaryPlayer?.pause()
            } catch (_: Exception) {}
            val pm = PlaybackManager.getInstance(applicationContext)
            pm.updatePlayingState(false)
            pm.savePlaybackState(wasPlaying = false)
            stopWidgetUpdateTimer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            try {
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.cancel(1)
            } catch (_: Exception) {}
            // 用户明确关闭 App：必须真正释放播放器，否则 AudioTrack 残留，
            // 下次启动会出现「上一首歌的声音卡住」。
            releasePlayersQuietly()
            updatePlaybackState()
            stopSelf()
            return
        }

        // When stopOnTaskRemoved is false (default):
        // Keep the foreground service and playback running seamlessly in the background.
        // We MUST NOT call super.onTaskRemoved(rootIntent) because MediaSessionService.onTaskRemoved
        // evaluates isPlaybackOngoing() == false (since Lune manages its own custom foreground notification
        // rather than Media3's internal MediaNotificationManager) and erroneously invokes stopSelf().
        Log.i("MusicService", "onTaskRemoved: keeping playback and notification active in background")
    }

    override fun onBind(intent: Intent?): IBinder? {
        val action = intent?.action
        return if (action == "androidx.media3.session.MediaSessionService" ||
            action == "androidx.media3.session.MediaLibraryService" ||
            action == "android.media.browse.MediaBrowserService") {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (!isPlaying() && isNotificationDismissed) {
            return
        }
        val pm = PlaybackManager.getInstance(this)
        val song = pm.currentSong
        if (song != null) {
            showNotification(song, isPlaying())
        }
    }

    private fun <T> Deferred<T>.asListenableFuture(): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                settable.set(await())
            } catch (e: Throwable) {
                settable.setException(e)
            }
        }
        return settable
    }

    private fun createBrowsableItem(mediaId: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    // 媒体项 id 一律用 uid：在线歌的 id 恒为 0，用 id 会让所有在线歌对外都是 "song_0"，
    // 系统媒体控件（蓝牙/车机/通知）分不清是哪首，点「下一首」时常回到同一首。
    private fun createPlayableItem(song: Song, mediaId: String = "song_${song.uid}"): MediaItem {
        val artworkUri = if (song.coverUrl != null) {
            Uri.parse(song.coverUrl)
        } else {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(song.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun handlePlayMediaId(mediaId: String) {
        serviceScope.launch {
            val playbackManager = PlaybackManager.getInstance(applicationContext)
            val provider = MusicProvider(applicationContext)
            val db = MusicDatabase.getDatabase(applicationContext)

            val hiddenFolders = settingsManager.hiddenFolders
            when {
                mediaId.startsWith("song_allsongs_") -> {
                    val songUid = mediaId.substringAfter("song_allsongs_")
                    val songs = provider.getCachedSongs().filter { !hiddenFolders.contains(it.folderName) }
                    val targetSong = songs.find { it.uid == songUid } ?: return@launch
                    playbackManager.play(targetSong, songs, -100L, category = "ALL", shuffleMode = playbackManager.isShuffle)
                }
                mediaId.startsWith("song_favs_") -> {
                    val songUid = mediaId.substringAfter("song_favs_")
                    val songs = provider.getCachedSongs().filter { it.isFavorite && !hiddenFolders.contains(it.folderName) }
                    val targetSong = songs.find { it.uid == songUid } ?: return@launch
                    playbackManager.play(targetSong, songs, -200L, category = "FAVORITES")
                }
                mediaId.startsWith("song_playlist_") -> {
                    // id 格式：song_playlist_<playlistId>_<songUid>，uid 里可能含冒号但不会含下划线
                    val rest = mediaId.removePrefix("song_playlist_")
                    val playlistId = rest.substringBefore("_").toLongOrNull() ?: return@launch
                    val songUid = rest.substringAfter("_", "")
                    if (songUid.isBlank()) return@launch

                    val songIds = db.playlistDao().getSongIdsForPlaylist(playlistId)
                    val allCached = provider.getCachedSongs()
                    val playlistSongs = songIds.mapNotNull { id -> allCached.find { it.id == id } }
                    val targetSong = playlistSongs.find { it.uid == songUid } ?: return@launch
                    playbackManager.play(targetSong, playlistSongs, playlistId, category = "PLAYLISTS")
                }
            }
        }
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.async {
                val mediaItems = mutableListOf<MediaItem>()
                val provider = MusicProvider(applicationContext)
                val db = MusicDatabase.getDatabase(applicationContext)

                try {
                    when (parentId) {
                        "root" -> {
                            mediaItems.add(createBrowsableItem("all_songs", getString(R.string.tab_songs)))
                            mediaItems.add(createBrowsableItem("favorites", getString(R.string.tab_favorites)))
                            mediaItems.add(createBrowsableItem("playlists", getString(R.string.playlists)))
                        }
                        "all_songs" -> {
                            val hidden = settingsManager.hiddenFolders
                            val songs = provider.getCachedSongs().filter { !hidden.contains(it.folderName) }
                            for (song in songs) {
                                mediaItems.add(createPlayableItem(song, "song_allsongs_${song.uid}"))
                            }
                        }
                        "favorites" -> {
                            val hidden = settingsManager.hiddenFolders
                            val songs = provider.getCachedSongs().filter { it.isFavorite && !hidden.contains(it.folderName) }
                            for (song in songs) {
                                mediaItems.add(createPlayableItem(song, "song_favs_${song.uid}"))
                            }
                        }
                        "playlists" -> {
                            val playlists = db.playlistDao().getAllPlaylists()
                            for (playlist in playlists) {
                                mediaItems.add(createBrowsableItem("playlist_${playlist.id}", playlist.name))
                            }
                        }
                        else -> {
                            if (parentId.startsWith("playlist_")) {
                                val playlistId = parentId.removePrefix("playlist_").toLongOrNull()
                                if (playlistId != null) {
                                    val songIds = db.playlistDao().getSongIdsForPlaylist(playlistId)
                                    val allCached = provider.getCachedSongs()
                                    val playlistSongs = songIds.mapNotNull { id -> allCached.find { it.id == id } }
                                    for (song in playlistSongs) {
                                        mediaItems.add(createPlayableItem(song, "song_playlist_${playlistId}_${song.uid}"))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params)
            }.asListenableFuture()
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val targetItem = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            if (targetItem != null) {
                handlePlayMediaId(targetItem.mediaId)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val targetItem = mediaItems.firstOrNull()
            if (targetItem != null) {
                handlePlayMediaId(targetItem.mediaId)
            }
            return Futures.immediateFuture(mediaItems)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val pm = PlaybackManager.getInstance(applicationContext)
            when (customCommand.customAction) {
                ACTION_SHUFFLE -> {
                    pm.toggleShuffle()
                    currentSong()?.let { showNotification(it, isPlaying()) }
                    updatePlaybackState()
                }
                ACTION_FAVORITE -> {
                    pm.toggleFavorite(onFavoriteToggled = { updatedSong ->
                        val provider = MusicProvider(applicationContext)
                        provider.updateSongInCache(updatedSong)
                        showNotification(updatedSong, isPlaying())
                        updatePlaybackState()
                    })
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
                .build()
            val customLayout = buildCustomLayout()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setCustomLayout(customLayout)
                .setMediaButtonPreferences(customLayout)
                .build()
        }
    }

    private fun buildCustomLayout(): ImmutableList<CommandButton> {
        val pm = PlaybackManager.getInstance(this)
        val isShuffle = pm.isShuffle
        val shuffleIcon = if (isShuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        val shuffleRes = if (isShuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle

        val isFav = pm.currentSong?.isFavorite == true
        val favIcon = if (isFav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        val favRes = if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border

        return ImmutableList.of(
            CommandButton.Builder(shuffleIcon)
                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                .setDisplayName("Shuffle")
                .setIconResId(shuffleRes)
                .setCustomIconResId(shuffleRes)
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .setEnabled(true)
                .build(),
            CommandButton.Builder(favIcon)
                .setSessionCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
                .setDisplayName("Favorite")
                .setIconResId(favRes)
                .setCustomIconResId(favRes)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .setEnabled(true)
                .build()
        )
    }

    private fun ensureMediaSession() {
        if (lunePlayerAdapter == null) {
            lunePlayerAdapter = LuneAudioPlayerAdapter(this)
        }
        val currentSession = mediaSession
        if (currentSession == null) {
            val sessionIntent = Intent(this, Lune::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val sessionPendingIntent = PendingIntent.getActivity(
                this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE
            )
            val initialLayout = buildCustomLayout()
            mediaSession = MediaLibrarySession.Builder(this, lunePlayerAdapter!!, librarySessionCallback)
                .setSessionActivity(sessionPendingIntent)
                .setCustomLayout(initialLayout)
                .setMediaButtonPreferences(initialLayout)
                .build()

            val notificationHints = Bundle().apply {
                putBoolean("androidx.media3.session.MediaNotificationManager", true)
            }
            MediaController.Builder(this, mediaSession!!.token)
                .setConnectionHints(notificationHints)
                .buildAsync()
        }
    }

    /**
     * 装载数据源。
     *
     * 本地歌曲走 content:// 或文件路径；在线歌曲走 LxStreamProxy 的本地代理地址，
     * 直链解析、防盗链 header、过期重试都在代理层完成。
     */
    private fun setPlayerDataSource(player: ExoPlayerWrapper, song: Song): Boolean {
        lastPlayedSong = song
        val uri: android.net.Uri = if (song.isOnline) {
            val repo = com.lunelx.music.lx.LxRepository.getInstance(applicationContext)
            val proxyUrl = repo.streamUrlFor(song, repo.preferredQuality)
            if (proxyUrl == null) {
                Log.e("MusicService", "online song without resolvable stream: ${song.title}")
                return false
            }
            android.net.Uri.parse(proxyUrl)
        } else {
            song.uri
        }
        return try {
            player.setDataSource(uri)
            true
        } catch (e: Exception) {
            Log.e("MusicService", "setDataSource failed for ${song.title} ($uri)", e)
            false
        }
    }

    fun playSong(song: Song) {
        isNotificationDismissed = false
        isCrossfading = false
        PlaybackManager.getInstance(applicationContext).isTransitioning = false
        monitorJob?.cancel()
        requestAudioFocus()
        acquireWakeLock()
        updateWidget()

        // Promote service to foreground immediately with the active song
        showNotification(song, true)
        mediaPlayer?.release()
        secondaryPlayer?.release()
        secondaryPlayer = null

        val pm = PlaybackManager.getInstance(applicationContext)
        lateinit var player: ExoPlayerWrapper
        player = ExoPlayerWrapper(
            context = applicationContext,
            onCompleted = {
                if (!isCrossfading) {
                    PlaybackManager.getInstance(applicationContext).playNextFromService(true)
                }
            },
            onError = { message ->
                Log.e("MusicService", "player error in playSong: $message")
                PlaybackManager.getInstance(applicationContext).playNextFromService(true)
            },
            onPrepared = {
                try {
                    acquireWakeLock()
                    setupAudioFx(player.audioSessionId, false)
                    isDucked = false
                    applyBalance(PlaybackManager.getInstance(applicationContext).balance)
                    player.start()
                    updatePlaybackState()

                    serviceScope.launch {
                        val art = fetchAlbumArt(song)
                        updateMetadata(song, art)
                        showNotification(song, true, art)
                        updateWidget()
                        PlaybackManager.getInstance(applicationContext).clearLyrics()
                        extractLyrics(song)
                    }
                } catch (e: Exception) {
                    Log.e("MusicService", "Error during onPrepared in playSong", e)
                }
            },
        )

        if (!setPlayerDataSource(player, song)) {
            Log.e("MusicService", "Failed to load data source for song: ${song.title} (${song.uri})")
            player.release()
            mediaPlayer = null
            releaseWakeLock()
            PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
            return
        }

        try {
            player.isLooping = pm.shouldLoopCurrentSong()
            mediaPlayer = player
            player.start()
        } catch (e: Exception) {
            Log.e("MusicService", "Error configuring player in playSong", e)
            player.release()
            mediaPlayer = null
            releaseWakeLock()
            PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
        }
    }

    fun crossfadeToSong(song: Song) {
        if (!isCrossfading) {
            val mp = mediaPlayer
            val fadeMs = if (settingsManager.isCrossfadeCustomDuration)
                settingsManager.crossfadeDurationSeconds * 1000L else 12000L
            val remaining = if (mp != null) (mp.duration - mp.currentPosition).toLong() else fadeMs
            performCrossfade(song, if (remaining in 1..fadeMs) remaining else fadeMs)
        }
    }

    private var monitorJob: Job? = null
    private fun startCrossfadeMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            var lastPos = 0
            while (isActive) {
                val playbackManager = PlaybackManager.getInstance(applicationContext)
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    val currentPos = mp.currentPosition
                    val duration = mp.duration

                    // Detect seamless loop wrap-around (position resetting from end to start)
                    if (mp.isLooping && duration > 1000 && lastPos > (duration * 0.8f) && currentPos < (duration * 0.2f)) {
                        Log.d("MusicService", "Seamless loop wrap-around detected: $lastPos -> $currentPos ms")
                        updatePlaybackState()
                        playbackManager.currentSong?.let { song ->
                            playbackManager.onSongLooped(song)
                        }
                    }
                    lastPos = currentPos

                    // If track is looping, skip crossfade so seamless loop is not interrupted
                    if (mp.isLooping || playbackManager.shouldLoopCurrentSong()) {
                        delay(200)
                        continue
                    }

                    if ((playbackManager.isCrossfade || playbackManager.isAutomix) && !isCrossfading) {
                        val remaining = duration - currentPos
                        val maxTriggerMs = if (settingsManager.isCrossfadeCustomDuration)
                            settingsManager.crossfadeDurationSeconds * 1000L else 12000L

                        // Only fire if we have enough time left and are nearing the end
                        if (duration > maxTriggerMs && remaining in 1..maxTriggerMs && currentPos > (duration / 2)) {
                            val nextSong = playbackManager.getNextSong()
                            if (nextSong != null) {
                                Log.d("MusicService", "Crossfade triggered with duration: $remaining ms")
                                // We pass the remaining real time as the transition duration
                                performCrossfade(nextSong, remaining.toLong())
                            }
                        }
                    }
                } else {
                    lastPos = 0
                }
                delay(200)
            }
        }
    }

    /**
     * 交叉淡化已移除。
     *
     * ExoPlayer 无原生 crossfade，自行实现双实例交叉需要两套音效链与
     * 独立的 audio session，复杂度和爆音风险都高。这里退化为直接切歌，
     * 保留方法名以兼容 [crossfadeToSong] 等调用点。
     */
    private fun performCrossfade(nextSong: Song, fadeDurationMs: Long) {
        isCrossfading = false
        PlaybackManager.getInstance(applicationContext).isTransitioning = false
        playSong(nextSong)
    }

    private suspend fun fetchAlbumArt(song: Song): android.graphics.Bitmap? {
        val loader = this.imageLoader
        val artUri = if (song.coverUrl != null) {
            Uri.parse(song.coverUrl)
        } else if (song.albumArtUri != null) {
            song.albumArtUri
        } else if (song.albumId > 0) {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
        } else {
            song.uri
        }
        val request = ImageRequest.Builder(this)
            .data(artUri)
            .size(512, 512)
            .allowHardware(false)
            .build()

        val result = loader.execute(request)
        var bitmap = (result as? SuccessResult)?.drawable?.let {
            val bmp = android.graphics.Bitmap.createBitmap(
                it.intrinsicWidth.coerceAtLeast(1),
                it.intrinsicHeight.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            it.setBounds(0, 0, canvas.width, canvas.height)
            it.draw(canvas)
            bmp
        }

        if (bitmap == null) {
            try {
                val retriever = MediaMetadataRetriever()
                if (song.uri.scheme == "file" && song.path.isNotBlank() && java.io.File(song.path).exists()) {
                    retriever.setDataSource(song.path)
                } else {
                    retriever.setDataSource(this, song.uri)
                }
                val pic = retriever.embeddedPicture
                retriever.release()
                if (pic != null) {
                    val boundsOpts = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size, boundsOpts)
                    var inSample = 1
                    while (boundsOpts.outWidth / (inSample * 2) >= 512 && boundsOpts.outHeight / (inSample * 2) >= 512) {
                        inSample *= 2
                    }
                    val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = inSample
                    }
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size, decodeOpts)
                }
            } catch (_: Exception) {}
        }

        return bitmap
    }

    fun pause() {
        pauseTimeoutJob?.cancel()
        // Persist playback state immediately when paused (do NOT wait for timeout)
        PlaybackManager.getInstance(applicationContext).savePlaybackState(wasPlaying = false)

        pauseTimeoutJob = serviceScope.launch {
            delay(PAUSE_TIMEOUT_MS)
            isNotificationDismissed = true
            abandonAudioFocus()
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            try {
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.cancel(1)
            } catch (_: Exception) {}
            // 暂停超时后彻底释放播放器。
            // 只 pause 不 release 会留下一个仍占用 AudioTrack 的 ExoPlayer 实例，
            // 进程被系统回收时 AudioTrack 可能残留，表现为「关掉 App 后仍有声音卡住」。
            releasePlayersQuietly()
            stopSelf()
        }

        try {
            mediaPlayer?.pause()
            secondaryPlayer?.pause()
        } catch (e: Exception) {
            Log.e("MusicService", "Error pausing player", e)
        }
        releaseWakeLock()
        PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
        updatePlaybackState()
        stopWidgetUpdateTimer()
        updateWidget()
        serviceScope.launch {
            val song = currentSong() ?: return@launch
            val art = fetchAlbumArt(song)
            showNotification(song, false, art)
        }
    }

    fun resume() {
        if (!requestAudioFocus()) {
            if (resumeOnFocusGain) {
                Log.d("MusicService", "Audio focus delayed; playback will resume when granted")
            } else {
                Log.w("MusicService", "Audio focus request failed, cannot resume")
            }
            PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
            return
        }
        resumeInternal()
    }

    private fun resumeInternal() {
        val pm = PlaybackManager.getInstance(applicationContext)
        if (pm.isQueueFinished) {
            pm.resume()
            return
        }
        isNotificationDismissed = false
        pauseTimeoutJob?.cancel()
        acquireWakeLock()

        if (mediaPlayer == null) {
            if (pm.currentSong == null && !pm.stateRestored) {
                pm.restorePlaybackState()
            }
            val song = pm.currentSong
            if (song != null) {
                val savedPos = pm.playbackStateSaver.restore()?.playbackPositionMs ?: 0L
                restorePlayback(song, savedPos, andPlay = true)
                return
            } else {
                pm.updatePlayingState(false)
                updateWidget()
                return
            }
        }

        try {
            isDucked = false
            val (left, right) = getCurrentTargetVolumes()
            mediaPlayer?.setVolume(left, right)
            secondaryPlayer?.setVolume(left, right)

            mediaPlayer?.start()
            secondaryPlayer?.start()
        } catch (e: Exception) {
            Log.e("MusicService", "Failed to start player on resume", e)
            pm.updatePlayingState(false)
            return
        }
        pm.updatePlayingState(true)
        updatePlaybackState()
        startWidgetUpdateTimer()
        updateWidget()
        serviceScope.launch {
            val song = currentSong() ?: return@launch
            val art = fetchAlbumArt(song)
            showNotification(song, true, art)
        }
    }

    fun hasPlayer(): Boolean = mediaPlayer != null
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true || secondaryPlayer?.isPlaying == true
    fun currentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun duration(): Int = mediaPlayer?.duration ?: 0
    fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: 0

    fun setLooping(looping: Boolean) {
        try {
            mediaPlayer?.isLooping = looping
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 变速变调。ExoPlayer 用 PlaybackParameters(speed, pitch) 一次设置。
     */
    fun setPlaybackParams(speed: Float, pitch: Float) {
        try {
            mediaPlayer?.raw()?.playbackParameters =
                androidx.media3.common.PlaybackParameters(speed, pitch)
        } catch (e: Exception) {
            Log.w("MusicService", "setPlaybackParams failed: ${e.message}")
        }
    }

    fun seekTo(pos: Int) {
        mediaPlayer?.seekTo(pos)
        updatePlaybackState()
        updateWidget()
    }

    /** Seeks to position 0 without resuming playback. Called when queue ends naturally. */
    fun resetPlayerProgress() {
        isCrossfading = false
        PlaybackManager.getInstance(applicationContext).isTransitioning = false
        monitorJob?.cancel()

        try {
            mediaPlayer?.seekTo(0)
        } catch (_: Exception) {}
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {}
        try {
            secondaryPlayer?.pause()
        } catch (_: Exception) {}

        PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
        updatePlaybackState()
        stopWidgetUpdateTimer()
        updateWidget()

        isNotificationDismissed = false
        pauseTimeoutJob?.cancel()
        pauseTimeoutJob = serviceScope.launch {
            delay(PAUSE_TIMEOUT_MS)
            isNotificationDismissed = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            try {
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.cancel(1)
            } catch (_: Exception) {}
            stopSelf()
        }

        serviceScope.launch {
            val song = currentSong() ?: return@launch
            val art = fetchAlbumArt(song)
            showNotification(song, false, art)
        }
    }

    fun restorePlayback(song: Song, positionMs: Long, andPlay: Boolean) {
        isCrossfading = false
        PlaybackManager.getInstance(applicationContext).isTransitioning = false
        monitorJob?.cancel()
        if (andPlay) {
            requestAudioFocus()
            acquireWakeLock()
        }

        mediaPlayer?.release()
        secondaryPlayer?.release()
        secondaryPlayer = null

        val pm = PlaybackManager.getInstance(applicationContext)
        lateinit var player: ExoPlayerWrapper
        player = ExoPlayerWrapper(
            context = applicationContext,
            onCompleted = {
                if (!isCrossfading) {
                    PlaybackManager.getInstance(applicationContext).playNextFromService(true)
                }
            },
            onError = { message ->
                Log.e("MusicService", "player error in restorePlayback: $message")
            },
            onPrepared = {
                try {
                    setupAudioFx(player.audioSessionId, false)
                    isDucked = false
                    applyBalance(pm.balance)
                    player.seekTo(positionMs.toInt())
                    if (andPlay) {
                        acquireWakeLock()
                        player.start()
                    }
                    pm.updatePlayingState(andPlay)
                    if (andPlay) startWidgetUpdateTimer() else stopWidgetUpdateTimer()
                    updatePlaybackState()
                    serviceScope.launch {
                        val art = fetchAlbumArt(song)
                        updateMetadata(song, art)
                        showNotification(song, andPlay, art)
                        pm.clearLyrics()
                        extractLyrics(song)
                        updateWidget()
                    }
                } catch (e: Exception) {
                    Log.e("MusicService", "Error during onPrepared in restorePlayback", e)
                }
            },
        )

        if (!setPlayerDataSource(player, song)) {
            Log.w("MusicService", "Failed to set data source for restorePlayback: ${song.title} (${song.uri})")
            player.release()
            mediaPlayer = null
            if (andPlay) releaseWakeLock()
            PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
            return
        }

        try {
            player.isLooping = pm.shouldLoopCurrentSong()
            mediaPlayer = player
            // ⚠️ 这里原来无条件 player.start()，把 andPlay=false 的「恢复但保持暂停」
            // 变成了「一打开 App 就自动播放上次那首」。只有用户真的点了播放
            // （andPlay=true）才出声；否则只 prepare()，让 onPrepared 去 seek 到
            // 上次进度并刷新通知/时长，等用户手动点播放。
            if (andPlay) player.start() else player.prepare()
        } catch (e: Exception) {
            Log.e("MusicService", "Error configuring player in restorePlayback", e)
            player.release()
            mediaPlayer = null
            if (andPlay) releaseWakeLock()
            PlaybackManager.getInstance(applicationContext).updatePlayingState(false)
        }
    }

    private fun extractLyrics(song: Song) {
        Log.d("MusicService", "Extracting lyrics for: ${song.title}")
        serviceScope.launch(Dispatchers.IO) {
            val playbackManager = PlaybackManager.getInstance(applicationContext)

            withContext(Dispatchers.Main) {
                playbackManager.updateLyrics(null)
            }

            // 在线歌曲：歌词来自音源脚本，不走本地文件链路
            if (song.isOnline) {
                extractOnlineLyrics(song, playbackManager)
                return@launch
            }

            // 0. Check custom lyrics saved by user
            val customLyrics = LyricsStorageManager.getInstance(applicationContext).getCustomLyrics(song)
            if (!customLyrics.isNullOrBlank()) {
                Log.d("MusicService", "Found custom user lyrics for: ${song.title}")
                withContext(Dispatchers.Main) {
                    playbackManager.updateLyrics(customLyrics)
                }
                return@launch
            }

            // 1. Try to find a .lrc file in the same directory, subdirectories or Music/Lyrics
            val songFile = File(song.path)
            val parentDir = songFile.parentFile
            val baseName = songFile.nameWithoutExtension
            val possibleLrcFiles = mutableListOf<File>()

            if (parentDir != null && parentDir.exists()) {
                possibleLrcFiles.add(File(parentDir, "$baseName.lrc"))
                possibleLrcFiles.add(File(parentDir, "$baseName.LRC"))
                val subLyrics1 = File(parentDir, "Lyrics")
                val subLyrics2 = File(parentDir, "lyrics")
                if (subLyrics1.isDirectory) {
                    possibleLrcFiles.add(File(subLyrics1, "$baseName.lrc"))
                    possibleLrcFiles.add(File(subLyrics1, "$baseName.LRC"))
                }
                if (subLyrics2.isDirectory) {
                    possibleLrcFiles.add(File(subLyrics2, "$baseName.lrc"))
                    possibleLrcFiles.add(File(subLyrics2, "$baseName.LRC"))
                }
            }

            try {
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val globalLyrics = File(musicDir, "Lyrics")
                if (globalLyrics.isDirectory) {
                    possibleLrcFiles.add(File(globalLyrics, "$baseName.lrc"))
                    possibleLrcFiles.add(File(globalLyrics, "$baseName.LRC"))
                }
            } catch (_: Exception) {}

            val lrcFile = possibleLrcFiles.firstOrNull { it.exists() && it.isFile }

            if (lrcFile != null) {
                try {
                    Log.d("MusicService", "Found .lrc file: ${lrcFile.absolutePath}")
                    val lrcContent = CharsetUtils.readText(lrcFile)
                    if (lrcContent.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            playbackManager.updateLyrics(lrcContent)
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. Try to extract embedded lyrics via Jaudiotagger (Best for FLAC/Vorbis/ID3)
            try {
                val f = File(song.path)
                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(f)
                val tag = audioFile.tag
                if (tag != null) {
                    var embedded = tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS)
                    if (embedded.isNullOrBlank()) {
                        embedded = tag.getFirst("USLT")
                    }
                    if (embedded.isNullOrBlank()) {
                        embedded = tag.getFirst("SYLT")
                    }

                    if (!embedded.isNullOrBlank()) {
                        Log.i("MusicService", "Jaudiotagger extraction success. Length: ${embedded.length}")
                        val sanitized = CharsetUtils.sanitizeText(embedded)
                        withContext(Dispatchers.Main) {
                            playbackManager.updateLyrics(sanitized)
                        }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicService", "Jaudiotagger failed: ${e.message}")
            }

            // 3. Fallback to MediaMetadataRetriever (Native)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, song.uri)
                var embeddedLyrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    retriever.extractMetadata(13 /* MediaMetadataRetriever.METADATA_KEY_LYRIC */)
                } else null

                // FLAC/Vorbis Fallback: If still null, try reading the file header for "LYRICS=" or "UNSYNCEDLYRICS="
                Log.i("MusicService", "Extracted lyrics length from retriever: ${embeddedLyrics?.length ?: 0}")

                if (embeddedLyrics.isNullOrBlank()) {
                    Log.i("MusicService", "No lyrics in retriever, trying manual scan on ${song.path}")
                    embeddedLyrics = tryExtractManual(song.path)
                }

                Log.i("MusicService", "Final extracted lyrics length: ${embeddedLyrics?.length ?: 0}")
                if (embeddedLyrics != null) {
                    Log.i("MusicService", "Final lyrics snippet: ${embeddedLyrics.take(50)}...")
                }

                withContext(Dispatchers.Main) {
                    playbackManager.updateLyrics(embeddedLyrics)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }
    }

    /**
     * 在线歌曲歌词获取。
     *
     * 音源脚本的 `lyric` 事件返回 lx-music 标准结构：
     * - `lyric`   原文（LRC 或逐字格式）
     * - `tlyric`  中文翻译
     * - `rlyric`  罗马音
     * - `lxlyric` 逐字歌词
     *
     * 本项目播放器目前只渲染标准 LRC，因此优先用 `lyric`；
     * 若有翻译则合并成双语 LRC（原文行 + 紧随其后的译文行）。
     */
    private suspend fun extractOnlineLyrics(
        song: Song,
        playbackManager: PlaybackManager,
    ) {
        val repo = com.lunelx.music.lx.LxRepository.getInstance(applicationContext)
        val result = runCatching { repo.fetchLyric(song) }
            .onFailure { Log.w("MusicService", "online lyric failed: ${it.message}") }
            .getOrNull()

        val merged = result?.let { mergeLyrics(it.lyric, it.tlyric) }
        if (merged.isNullOrBlank()) {
            Log.d("MusicService", "no online lyrics for ${song.title}")
            withContext(Dispatchers.Main) { playbackManager.updateLyrics(null) }
            return
        }

        // 回填到 Song，供 UI 与缓存复用
        withContext(Dispatchers.Main) {
            playbackManager.updateLyrics(merged)
        }
        Log.i("MusicService", "online lyrics loaded for ${song.title}, ${merged.length} chars")
    }

    /**
     * 把原文 LRC 与译文 LRC 合并为双语歌词。
     *
     * 做法：解析出两个时间戳表，按时间对齐；原文行后插入同一时间点的译文行。
     * 译文缺失时原样返回原文。
     */
    private fun mergeLyrics(lyric: String?, tlyric: String?): String? {
        if (lyric.isNullOrBlank()) return lyric
        if (tlyric.isNullOrBlank()) return lyric

        val timeTag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        fun parse(text: String): List<Pair<Long, String>> {
            val out = mutableListOf<Pair<Long, String>>()
            for (raw in text.lineSequence()) {
                val tags = timeTag.findAll(raw).toList()
                if (tags.isEmpty()) continue
                val body = raw.substring(tags.last().range.last + 1).trim()
                if (body.isEmpty()) continue
                for (t in tags) {
                    val m = t.groupValues
                    val min = m[1].toLongOrNull() ?: continue
                    val sec = m[2].toLongOrNull() ?: continue
                    val frac = m[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    out.add((min * 60_000 + sec * 1_000 + frac) to body)
                }
            }
            return out
        }

        val original = parse(lyric)
        if (original.isEmpty()) return lyric
        val translated = parse(tlyric).toMap()

        val sb = StringBuilder()
        for ((ms, text) in original) {
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1_000
            val frac = ms % 1_000
            sb.append(String.format("[%02d:%02d.%03d]", min, sec, frac)).append(text).append('\n')
            translated[ms]?.let { tr ->
                sb.append(String.format("[%02d:%02d.%03d]", min, sec, frac)).append(tr).append('\n')
            }
        }
        return sb.toString()
    }

    private fun tryExtractManual(path: String): String? {
        try {
            val file = File(path)
            if (!file.exists()) return null

            // Read first 1MB. FLAC/MP3 metadata blocks are usually within this range.
            val bufferSize = 1024 * 1024
            val buffer = ByteArray(bufferSize.coerceAtMost(file.length().toInt()))
            file.inputStream().use { it.read(buffer) }

            val tags = listOf("UNSYNCEDLYRICS=", "LYRICS=", "USLT=", "unsyncedlyrics=", "lyrics=")
            for (tag in tags) {
                val tagBytes = tag.toByteArray(Charsets.UTF_8)
                val index = findBytes(buffer, tagBytes)
                if (index != -1) {
                    // Vorbis Comment Check: The 4 bytes before the tag name often store the length (Little Endian)
                    // Format: [Length (4 bytes)] [NAME=VALUE]
                    // Since 'index' points to 'NAME', the length field is at index-4
                    if (index >= 4) {
                        val len = (buffer[index - 4].toInt() and 0xFF) or
                                 ((buffer[index - 3].toInt() and 0xFF) shl 8) or
                                 ((buffer[index - 2].toInt() and 0xFF) shl 16) or
                                 ((buffer[index - 1].toInt() and 0xFF) shl 24)

                        // If length is plausible (e.g. 100 bytes to 128KB), use it
                        if (len in tagBytes.size..131072) {
                            val totalLength = len - tagBytes.size
                            if (index + tagBytes.size + totalLength <= buffer.size) {
                                val rawBytes = buffer.copyOfRange(index + tagBytes.size, index + tagBytes.size + totalLength)
                                val result = CharsetUtils.decodeBytes(rawBytes).trim()
                                Log.i("MusicService", "SUCCESS: Extracted via Vorbis length: ${result.length} chars")
                                return result
                            }
                        }
                    }

                    // Fallback to previous regex if Vorbis check fails
                    val start = index + tagBytes.size
                    val length = (65536).coerceAtMost(buffer.size - start)
                    val rawBytes = buffer.copyOfRange(start, start + length)
                    val raw = CharsetUtils.decodeBytes(rawBytes)
                    val nextTagPattern = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]|\\r?\\n[A-Z0-9_]{3,}=")
                    val matcher = nextTagPattern.matcher(raw)
                    val end = if (matcher.find()) matcher.start() else raw.length

                    val result = raw.substring(0, end).trim()
                    if (result.length > 5) return result
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        for (i in 0..haystack.size - needle.size) {
            var found = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun bitmapToSafeByteArray(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        return try {
            val maxDim = 320
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val targetW = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
                val targetH = if (ratio >= 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            if (scaled != bitmap) {
                scaled.recycle()
            }
            stream.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun updateMetadata(song: Song, art: android.graphics.Bitmap? = null) {
        ensureMediaSession()
        art?.let {
            lunePlayerAdapter?.currentArtworkData = bitmapToSafeByteArray(it)
        }
        lunePlayerAdapter?.notifyStateChanged()
        val customLayout = buildCustomLayout()
        mediaSession?.setCustomLayout(customLayout)
        mediaSession?.setMediaButtonPreferences(customLayout)
    }

    private fun updatePlaybackState() {
        ensureMediaSession()
        lunePlayerAdapter?.notifyStateChanged()
        val customLayout = buildCustomLayout()
        mediaSession?.setCustomLayout(customLayout)
        mediaSession?.setMediaButtonPreferences(customLayout)
    }

    private fun currentSong(): Song? {
        return PlaybackManager.getInstance(this).currentSong
    }

    private fun showNotification(song: Song, isPlaying: Boolean, art: android.graphics.Bitmap? = null) {
        if (!isPlaying && isNotificationDismissed) {
            return
        }

        if (art != null) {
            val scaled = if (art.width > 512 || art.height > 512) {
                val ratio = art.width.toFloat() / art.height.toFloat()
                val targetW = if (ratio >= 1f) 512 else (512 * ratio).toInt().coerceAtLeast(1)
                val targetH = if (ratio >= 1f) (512 / ratio).toInt().coerceAtLeast(1) else 512
                try {
                    Bitmap.createScaledBitmap(art, targetW, targetH, true)
                } catch (_: Exception) {
                    art
                }
            } else {
                art
            }
            currentNotificationArt = scaled
            currentNotificationArtSongUid = song.uid
        }

        // 只有「同一首歌」才沿用上一次的封面；别的歌就等它自己的 art（null 时不串图）
        val effectiveArt = if (currentNotificationArtSongUid == song.uid) currentNotificationArt else art

        val newArtBytes = if (effectiveArt != null) bitmapToSafeByteArray(effectiveArt) else null
        if (lunePlayerAdapter?.currentArtworkData?.contentEquals(newArtBytes) != true) {
            lunePlayerAdapter?.currentArtworkData = newArtBytes
            lunePlayerAdapter?.notifyStateChanged()
        }

        val channelId = "music_playback_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Music Playback", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, Lune::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                getServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                getServicePendingIntent(ACTION_PLAY)
            )
        }

        val pm = PlaybackManager.getInstance(this)
        val isShuffleOn = pm.isShuffle
        val isFav = song.isFavorite

        val shuffleAction = NotificationCompat.Action(
            if (isShuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle,
            "Shuffle",
            getServicePendingIntent(ACTION_SHUFFLE)
        )

        val favoriteAction = NotificationCompat.Action(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
            "Favorite",
            getServicePendingIntent(ACTION_FAVORITE)
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(effectiveArt)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setOngoing(isPlaying)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(getServicePendingIntent(ACTION_DISMISS))
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", getServicePendingIntent(ACTION_PREVIOUS))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", getServicePendingIntent(ACTION_NEXT))
            .addAction(shuffleAction)
            .addAction(favoriteAction)

        ensureMediaSession()
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.w("MusicService", "startForeground failed: ${e.message}")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(1, notification)
        }
    }

    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        val useForegroundService = action != ACTION_DISMISS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        return if (useForegroundService) {
            PendingIntent.getForegroundService(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    /**
     * 静默释放所有播放器与音效，用于暂停超时、任务移除等场景。
     *
     * 关键点：先淡出到 0 再 release，避免直接 release 造成的爆音；
     * 同时释放音效链，否则 Equalizer 等仍持有 audio session。
     */
    private fun releasePlayersQuietly() {
        try {
            mediaPlayer?.setVolume(0f, 0f)
            secondaryPlayer?.setVolume(0f, 0f)
            mediaPlayer?.stop()
            secondaryPlayer?.stop()
        } catch (_: Exception) {}
        runCatching {
            equalizer?.release(); equalizer = null
            bassBoost?.release(); bassBoost = null
            virtualizer?.release(); virtualizer = null
            secondaryEqualizer?.release(); secondaryEqualizer = null
            secondaryBassBoost?.release(); secondaryBassBoost = null
            secondaryVirtualizer?.release(); secondaryVirtualizer = null
        }
        runCatching { loudnessEffect?.releaseAll() }
        runCatching { reverbEffect?.releaseAll() }
        runCatching { dynamicsEffect?.releaseAll() }
        runCatching { visualizerStop() }
        runCatching { mediaPlayer?.release() }
        runCatching { secondaryPlayer?.release() }
        mediaPlayer = null
        secondaryPlayer = null
        Log.i("MusicService", "players released")
    }

    private fun visualizerStop() {
        runCatching {
            PlaybackManager.getInstance(applicationContext).stopVisualizer()
        }
    }

    override fun onDestroy() {
        becomingNoisyReceiver?.let { unregisterReceiver(it) }
        effectTransitionJob?.cancel()
        spatialRampJob?.cancel()
        pauseTimeoutJob?.cancel()
        try {
            PlaybackManager.getInstance(applicationContext).savePlaybackState(wasPlaying = false)
            mediaPlayer?.setVolume(0f, 0f)
            secondaryPlayer?.setVolume(0f, 0f)
            mediaPlayer?.stop()
            secondaryPlayer?.stop()
        } catch (_: Exception) {}
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        loudnessEffect?.releaseAll()
        reverbEffect?.releaseAll()
        dynamicsEffect?.releaseAll()
        secondaryEqualizer?.release()
        secondaryBassBoost?.release()
        secondaryVirtualizer?.release()
        mediaPlayer?.release()
        secondaryPlayer?.release()
        mediaSession?.release()
        mediaSession = null
        lunePlayerAdapter?.release()
        lunePlayerAdapter = null
        serviceScope.cancel()
        abandonAudioFocus()
        releaseWakeLock()
        wakeLock = null
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.cancel(1)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /**
     * Safely executes an effect modification (such as disabling an effect)
     * with a micro-fade (ramp-down -> action -> buffer settle -> ramp-up).
     * This eliminates DC offset bursts, filter transient pops, and residual buffer clicks
     * that occur when detaching or disabling DSP effects in direct hardware / AAudio exclusive mode.
     */
    internal fun executeEffectTransition(isDisabling: Boolean, action: () -> Unit) {
        if (!isPlaying() || isCrossfading) {
            // If not actively playing audio to the DAC or already in a crossfade volume ramp,
            // execute immediately without interfering with volume.
            action()
            return
        }

        if (!isDisabling) {
            // Enabling an effect is smooth as internal filter states start from zero
            action()
            return
        }

        effectTransitionJob?.cancel()
        effectTransitionJob = serviceScope.launch {
            val (baseLeft, baseRight) = getCurrentTargetVolumes()
            try {
                // Micro fade-out: smoothly attenuate to 0 over ~18ms (3 steps x 6ms)
                val steps = 3
                val stepDelay = 6L
                for (step in (steps - 1) downTo 0) {
                    val scale = step.toFloat() / steps
                    mediaPlayer?.setVolume(baseLeft * scale, baseRight * scale)
                    secondaryPlayer?.setVolume(baseLeft * scale, baseRight * scale)
                    delay(stepDelay)
                }
                mediaPlayer?.setVolume(0f, 0f)
                secondaryPlayer?.setVolume(0f, 0f)

                // Perform the DSP teardown / disable while DAC output is completely muted
                action()

                // Settle window (15ms) to allow hardware DMA / AAudio ringbuffers to clear
                delay(15L)

                // Micro fade-in: smoothly ramp back up to target volume over ~18ms
                for (step in 1..steps) {
                    val scale = step.toFloat() / steps
                    val (curLeft, curRight) = getCurrentTargetVolumes()
                    mediaPlayer?.setVolume(curLeft * scale, curRight * scale)
                    secondaryPlayer?.setVolume(curLeft * scale, curRight * scale)
                    delay(stepDelay)
                }
            } finally {
                if (!isCrossfading) {
                    val (curLeft, curRight) = getCurrentTargetVolumes()
                    mediaPlayer?.setVolume(curLeft, curRight)
                    secondaryPlayer?.setVolume(curLeft, curRight)
                }
            }
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        if (!enabled) {
            executeEffectTransition(true) {
                equalizer?.enabled = false
                secondaryEqualizer?.enabled = false
            }
        } else {
            effectTransitionJob?.cancel()
            equalizer?.enabled = true
            secondaryEqualizer?.enabled = true
        }
    }

    fun setEqBandLevel(band: Short, level: Short) {
        try {
            equalizer?.setBandLevel(band, level)
            secondaryEqualizer?.setBandLevel(band, level)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun useEqPreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
            secondaryEqualizer?.usePreset(presetIndex)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun setBassBoostEnabled(enabled: Boolean, onBeforeDisable: (() -> Unit)? = null) {
        if (!enabled) {
            executeEffectTransition(true) {
                onBeforeDisable?.invoke()
                bassBoost?.enabled = false
                secondaryBassBoost?.enabled = false
            }
        } else {
            effectTransitionJob?.cancel()
            bassBoost?.enabled = true
            secondaryBassBoost?.enabled = true
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength(settingsManager.bassBoostLevel.toShort())
            }
            if (secondaryBassBoost?.strengthSupported == true) {
                secondaryBassBoost?.setStrength(settingsManager.bassBoostLevel.toShort())
            }
        }
    }

    fun setBassBoostStrength(strength: Short) {
        if (bassBoost?.strengthSupported == true) {
            bassBoost?.setStrength(strength)
        }
        if (secondaryBassBoost?.strengthSupported == true) {
            secondaryBassBoost?.setStrength(strength)
        }
    }

    fun setReverbPreset(preset: Int) {
        if (preset == 0) {
            executeEffectTransition(true) {
                reverbEffect?.setPreset(0)
            }
        } else {
            effectTransitionJob?.cancel()
            reverbEffect?.setPreset(preset)
        }
    }

    fun setDynamicsPreset(preset: Int) {
        if (preset == 0) {
            executeEffectTransition(true) {
                dynamicsEffect?.setPreset(0)
            }
        } else {
            effectTransitionJob?.cancel()
            dynamicsEffect?.setPreset(preset)
        }
    }

    fun applyBalance(balance: Float) {
        val (left, right) = getCurrentTargetVolumes()
        mediaPlayer?.setVolume(left, right)
        secondaryPlayer?.setVolume(left, right)
    }

    fun setLoudnessEnabled(enabled: Boolean, onBeforeDisable: (() -> Unit)? = null) {
        if (!enabled) {
            executeEffectTransition(true) {
                onBeforeDisable?.invoke()
                loudnessEffect?.setEnabled(false)
            }
        } else {
            effectTransitionJob?.cancel()
            loudnessEffect?.setEnabled(true)
        }
    }

    fun setLoudnessGain(gain: Int) {
        loudnessEffect?.setTargetGain(gain)
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        spatialRampJob?.cancel()
        spatialRampJob = serviceScope.launch {
            val target = if (enabled) 800.toShort() else 0.toShort()
            val start = currentSpatialStrength
            val steps = 15

            if (enabled) {
                virtualizer?.enabled = true
                secondaryVirtualizer?.enabled = true
            }

            for (i in 0..steps) {
                val progress = i.toFloat() / steps
                val eased = if (enabled) 1f - (1f - progress) * (1f - progress)
                            else progress * progress
                val value = (start + (target - start) * eased)
                    .toInt().coerceIn(0, 1000).toShort()
                if (virtualizer?.strengthSupported == true) {
                    virtualizer?.setStrength(value)
                }
                if (secondaryVirtualizer?.strengthSupported == true) {
                    secondaryVirtualizer?.setStrength(value)
                }
                delay(10)
            }

            currentSpatialStrength = target

            if (!enabled) {
                executeEffectTransition(true) {
                    virtualizer?.enabled = false
                    secondaryVirtualizer?.enabled = false
                }
            }
        }
    }

    private fun startWidgetUpdateTimer() {
        widgetUpdateJob?.cancel()
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        widgetUpdateJob = serviceScope.launch {
            while (isActive) {
                if (isPlaying() && powerManager.isInteractive) {
                    updateWidget()
                }
                delay(1000)
            }
        }
    }

    private fun stopWidgetUpdateTimer() {
        widgetUpdateJob?.cancel()
    }

    private fun updateWidget() {
        val pm = PlaybackManager.getInstance(applicationContext)
        if (pm.currentSong == null && !pm.stateRestored) {
            pm.restorePlaybackState()
        }
        val song = currentSong()
        val isPlaying = isPlaying()

        serviceScope.launch {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            val componentName = ComponentName(applicationContext, LuneWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds.isEmpty()) return@launch

            // Fetch and process art only if song changed or cache is missing
            if (song != null && (lastSongForRounded != song || cachedRoundedArt == null)) {
                val art = fetchAlbumArt(song)
                if (art != null) {
                    lastSongForRounded = song
                    lastSongForBlur = song
                    withContext(Dispatchers.IO) {
                        cachedRoundedArt = when {
                            settingsManager.widgetCircularCover && settingsManager.widgetVinylCover ->
                                LuneWidgetProvider.getVinylRecordBitmap(art)
                            settingsManager.widgetCircularCover ->
                                LuneWidgetProvider.getCircularBitmap(art)
                            else ->
                                LuneWidgetProvider.getSquareScaledBitmap(art)
                        }
                        val blurRadius = (settingsManager.widgetBackgroundBlur * 0.5f).toInt().coerceIn(3, 50)
                        lastBlurredBitmap = LuneWidgetProvider.getBlurredBitmap(
                            applicationContext,
                            art,
                            blurRadius,
                            28,
                            settingsManager.widgetBackgroundDarkness
                        )
                    }
                } else {
                    lastSongForRounded = null
                    lastSongForBlur = null
                    cachedRoundedArt = null
                    lastBlurredBitmap = null
                }
            } else if (song == null) {
                lastSongForRounded = null
                lastSongForBlur = null
                cachedRoundedArt = null
                lastBlurredBitmap = null
            }

            for (appWidgetId in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val layoutResId = LuneWidgetProvider.getWidgetLayoutResId(options)
                val views = RemoteViews(packageName, layoutResId)
                LuneWidgetProvider.applyWidgetStyling(applicationContext, views, settingsManager)
                LuneWidgetProvider.applyWidgetResponsiveLayout(views, options)

                if (song != null) {
                    views.setTextViewText(R.id.widget_title, song.title)
                    views.setTextViewText(R.id.widget_artist, song.artist)

                    views.setImageViewResource(R.id.widget_play_pause,
                        if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)

                    // Audio Output
                    views.setImageViewResource(R.id.widget_output_icon, getOutputIconRes())
                    views.setTextViewText(R.id.widget_output_text, getOutputName())

                    if (cachedRoundedArt != null) {
                        views.setImageViewBitmap(R.id.widget_cover, cachedRoundedArt)
                    } else {
                        views.setImageViewResource(R.id.widget_cover, R.drawable.ic_lune_placeholder)
                    }

                    if (settingsManager.widgetUseSolidBackground) {
                        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        val solidColor = if (isNight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                        views.setImageViewBitmap(R.id.widget_blur_bg, LuneWidgetProvider.getSolidColorBitmap(solidColor))
                        views.setViewVisibility(R.id.widget_blur_bg, View.VISIBLE)
                    } else if (lastBlurredBitmap != null) {
                        views.setImageViewBitmap(R.id.widget_blur_bg, lastBlurredBitmap)
                        views.setViewVisibility(R.id.widget_blur_bg, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                    }
                } else {
                    views.setTextViewText(R.id.widget_title, getString(R.string.no_song_playing))
                    views.setTextViewText(R.id.widget_artist, "")

                    views.setImageViewResource(R.id.widget_cover, R.drawable.ic_lune_placeholder)
                    if (settingsManager.widgetUseSolidBackground) {
                        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        val solidColor = if (isNight) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                        views.setImageViewBitmap(R.id.widget_blur_bg, LuneWidgetProvider.getSolidColorBitmap(solidColor))
                        views.setViewVisibility(R.id.widget_blur_bg, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                    }
                }

                // Button Intents
                views.setOnClickPendingIntent(R.id.widget_play_pause, getWidgetServicePendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY))
                views.setOnClickPendingIntent(R.id.widget_prev, getWidgetServicePendingIntent(ACTION_PREVIOUS))
                views.setOnClickPendingIntent(R.id.widget_next, getWidgetServicePendingIntent(ACTION_NEXT))

                // Open App
                val intent = Intent(applicationContext, Lune::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun getWidgetServicePendingIntent(action: String): PendingIntent {
        return LuneWidgetProvider.getServicePendingIntent(this, action)
    }

    private fun getOutputIconRes(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return R.drawable.ic_bluetooth
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> return R.drawable.ic_headphones
                }
            }
        }
        return R.drawable.ic_speaker
    }

    private fun getOutputName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return getString(R.string.output_bluetooth)
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> return getString(R.string.output_headphones)
                }
            }
        }
        return getString(R.string.output_speaker)
    }
}