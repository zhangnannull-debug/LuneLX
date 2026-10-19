package com.lunelx.music.data

import androidx.room3.*
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * 歌曲覆盖信息（用户改过的元数据 + 收藏状态）。
 *
 * 主键是 [songUid]（`local:<MediaStoreId>` / `lx:<source>:<songmid>`）而**不是** MediaStore id：
 * 在线歌曲没有 MediaStore id（恒为 0），用 id 当主键会让所有在线歌共用同一行 ——
 * 收藏一首就等于收藏全部，改一次标题会串到别的歌上。
 */
@Entity(tableName = "song_overrides")
data class SongOverride(
    @PrimaryKey val songUid: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val coverUri: String? = null,
    val isFavorite: Boolean = false,
    /**
     * 在线歌曲的完整快照（[com.lunelx.music.tools.SongJson] 序列化的 Song JSON）。
     *
     * 在线歌曲不是 MediaStore 里的实体，重启后没有地方能拿到它；把整首 Song 存在这里，
     * 「收藏的在线歌」才能在重启后重新列出并播放（酷狗等音源还依赖 hash 等额外字段）。
     * 本地歌曲为 null（可以从音乐库重建）。
     */
    val meta: String? = null
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PlaylistSong(
    val playlistId: Long,
    val songId: Long,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val position: Int = 0
)

@Dao
interface SongOverrideDao {
    @Query("SELECT * FROM song_overrides")
    suspend fun getAllOverrides(): List<SongOverride>

    @Query("SELECT * FROM song_overrides WHERE isFavorite = 1")
    suspend fun getFavorites(): List<SongOverride>

    @Query("SELECT * FROM song_overrides WHERE songUid = :songUid")
    suspend fun getOverrideForSong(songUid: String): SongOverride?

    @Query("SELECT * FROM song_overrides WHERE songUid IN (:songUids)")
    suspend fun getOverridesForSongs(songUids: List<String>): List<SongOverride>

    /** 收藏的在线歌曲（uid 前缀 `lx:`），按加入顺序的逆序（最近的在前）。 */
    @Query("SELECT * FROM song_overrides WHERE isFavorite = 1 AND songUid LIKE 'lx:%' ORDER BY rowid DESC")
    suspend fun getFavoriteOnlineOverrides(): List<SongOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: SongOverride)

    @Delete
    suspend fun deleteOverride(override: SongOverride)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlist_songs")
    suspend fun getAllPlaylistMappings(): List<PlaylistSong>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(playlistSong: PlaylistSong)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongsToPlaylist(playlistSongs: List<PlaylistSong>)
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    suspend fun getSongIdsForPlaylist(playlistId: Long): List<Long>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    suspend fun getPlaylistSongs(playlistId: Long): List<PlaylistSong>

    @Update
    suspend fun updatePlaylistSongs(playlistSongs: List<PlaylistSong>)

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int?

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE songId = :songId")
    suspend fun getPlaylistCountForSong(songId: Long): Int
}

@Entity(tableName = "playback_stats")
data class PlaybackStats(
    // 歌曲统计用 uid：`SONG_local:123` / `SONG_lx:wy:0039MnYb0qxYhV`。
    // 曾经是 `SONG_${song.id}`，而在线歌 id 恒为 0 → 所有在线歌堆进同一行 `SONG_0`。
    @PrimaryKey val id: String, // e.g., "SONG_local:123", "PLAYLIST_456", "ARTIST_Queen"
    val type: String, // "SONG", "PLAYLIST", "ARTIST"
    val playCount: Long = 0,
    val totalTimeMs: Long = 0,
    val lastPlayed: Long = System.currentTimeMillis(),
    /**
     * 在线歌曲的完整快照（Song 的 JSON）。本地歌曲为 null —— 它们能从音乐库按 uid 反查，
     * 在线歌曲不能，所以统计页要靠这份快照才能把「最常播放」里的在线歌显示出来。
     */
    val meta: String? = null
)

@Dao
interface PlaybackStatsDao {
    @Query("SELECT * FROM playback_stats WHERE id = :id")
    suspend fun getStatsById(id: String): PlaybackStats?

    @Query("SELECT * FROM playback_stats WHERE type = :type ORDER BY playCount DESC, totalTimeMs DESC LIMIT :limit")
    fun getTopByCountFlow(type: String, limit: Int): kotlinx.coroutines.flow.Flow<List<PlaybackStats>>

    @Query("SELECT * FROM playback_stats WHERE type = :type ORDER BY totalTimeMs DESC, playCount DESC LIMIT :limit")
    fun getTopByTimeFlow(type: String, limit: Int): kotlinx.coroutines.flow.Flow<List<PlaybackStats>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: PlaybackStats)
}

@Database(
    entities = [SongOverride::class, Playlist::class, PlaylistSong::class, PlaybackStats::class],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7)
    ],
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songOverrideDao(): SongOverrideDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackStatsDao(): PlaybackStatsDao

    companion object {
        /**
         * 7 → 8：把「按 MediaStore id 索引」改成「按 uid 索引」。
         *
         * 1. `song_overrides` 的主键从 `songId: Long` 换成 `songUid: String`。
         *    老数据全部是本地歌曲，迁移成 `'local:' || songId`，收藏一条不丢。
         * 2. `playback_stats` 增加 `meta` 列（在线歌快照），并把历史 `SONG_<数字>`
         *    行改写为 `SONG_local:<数字>`，与新的写入格式对齐。
         *
         * 注意 Room 3 的 `Migration.migrate` 接收 `SQLiteConnection` 且是 suspend 的，
         * 不能用旧的 `SupportSQLiteDatabase.execSQL` 写法。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.exec(
                    "CREATE TABLE IF NOT EXISTS `song_overrides_new` (" +
                        "`songUid` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `album` TEXT, " +
                        "`genre` TEXT, `coverUri` TEXT, `isFavorite` INTEGER NOT NULL, `meta` TEXT, " +
                        "PRIMARY KEY(`songUid`))"
                )
                connection.exec(
                    "INSERT OR REPLACE INTO `song_overrides_new` " +
                        "(`songUid`,`title`,`artist`,`album`,`genre`,`coverUri`,`isFavorite`,`meta`) " +
                        "SELECT 'local:' || `songId`, `title`, `artist`, `album`, `genre`, " +
                        "`coverUri`, `isFavorite`, NULL FROM `song_overrides`"
                )
                connection.exec("DROP TABLE `song_overrides`")
                connection.exec("ALTER TABLE `song_overrides_new` RENAME TO `song_overrides`")

                connection.exec("ALTER TABLE `playback_stats` ADD COLUMN `meta` TEXT")
                // 只改「SONG_ + 纯数字」的老行；已经是新格式的行不匹配这个 GLOB
                connection.exec(
                    "UPDATE `playback_stats` SET `id` = 'SONG_local:' || substr(`id`, 6) " +
                        "WHERE `type` = 'SONG' AND `id` GLOB 'SONG_[0-9]*'"
                )
            }

            private fun SQLiteConnection.exec(sql: String) {
                prepare(sql).use { it.step() }
            }
        }

        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getDatabase(context: android.content.Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                )
                    .addMigrations(MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
