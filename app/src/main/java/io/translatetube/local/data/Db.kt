package io.translatetube.local.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class SourceType { YOUTUBE, LOCAL }

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val key: String,            // youtube:<videoId> 或 local:<uri>
    val videoId: String?,                   // YouTube id（本地为 null）
    val localUri: String?,                  // 本地文件 uri
    val title: String,
    val channel: String,
    val durationSec: Long,
    val thumbUrl: String?,
    val sourceType: SourceType,
    val addedAt: Long,
    val lastPlayedAt: Long,
    val watchedSec: Long = 0,
    val isFavorite: Boolean = false,
)

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos ORDER BY lastPlayedAt DESC")
    fun all(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE isFavorite = 1 ORDER BY lastPlayedAt DESC")
    fun favorites(): Flow<List<VideoEntity>>

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM videos WHERE isFavorite = 1")
    suspend fun countFavorites(): Int

    /** Clear ordinary history only; favorite rows and source files are preserved. */
    @Query("DELETE FROM videos WHERE isFavorite = 0")
    suspend fun deleteNonFavorites(): Int

    @Query("DELETE FROM videos")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM videos WHERE `key` = :key")
    suspend fun byKey(key: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(v: VideoEntity)

    @Update
    suspend fun update(v: VideoEntity)

    @Query("DELETE FROM videos WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("UPDATE videos SET isFavorite = :fav WHERE `key` = :key")
    suspend fun setFavorite(key: String, fav: Boolean)

    @Query("UPDATE videos SET watchedSec = :sec WHERE `key` = :key")
    suspend fun setWatched(key: String, sec: Long)
}

/** 字幕行缓存（原文+译文整片存 JSON） */
@Entity(tableName = "subtitle_cache")
data class SubtitleCacheEntity(
    @PrimaryKey val cacheKey: String,       // "<videoKey>|<tgtLang>"
    val srcLang: String,
    val linesJson: String,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val videoKey: String = "",
)

@Dao
interface SubtitleCacheDao {
    @Query("SELECT * FROM subtitle_cache WHERE cacheKey = :k")
    suspend fun get(k: String): SubtitleCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: SubtitleCacheEntity)

    @Query("DELETE FROM subtitle_cache WHERE cacheKey = :k")
    suspend fun delete(k: String)

    @Query("DELETE FROM subtitle_cache WHERE videoKey = :videoKey OR substr(cacheKey, 1, length(:keyPrefix)) = :keyPrefix")
    suspend fun deleteForVideo(videoKey: String, keyPrefix: String): Int

    @Query("DELETE FROM subtitle_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM subtitle_cache")
    suspend fun countEntries(): Int

    /** Byte count for stored JSON payloads, not a character count. */
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(linesJson AS BLOB))), 0) FROM subtitle_cache")
    suspend fun payloadBytes(): Long
}

/** 用户配置的云翻译 API */
@Entity(tableName = "api_profiles")
data class ApiProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,                       // OPENAI / GEMINI / GCLOUD / DEEPL / BAIDU_API / AZURE / TENCENT / ALIYUN / LIBRETRANSLATE
    val baseUrl: String,                    // OPENAI: https://api.xx.com/v1 类
    val apiKey: String,                     // AES 加密存储
    val model: String,                      // 选定的翻译模型（传统引擎为空）
    val region: String,                     // Azure region
    val enabled: Boolean = true,
    val charsUsed: Long = 0,                // 用量统计（字符）
    @ColumnInfo(defaultValue = "''") val credentialsJson: String = "", // AES 加密的额外凭据（SecretId / APP ID 等）
)

@Dao
interface ApiProfileDao {
    @Query("SELECT * FROM api_profiles ORDER BY id")
    fun all(): Flow<List<ApiProfile>>

    @Query("SELECT * FROM api_profiles WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<ApiProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ApiProfile): Long

    @Query("DELETE FROM api_profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM api_profiles WHERE id = :id")
    suspend fun byId(id: Long): ApiProfile?
}

@Database(
    entities = [VideoEntity::class, SubtitleCacheEntity::class, ApiProfile::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun subtitleCacheDao(): SubtitleCacheDao
    abstract fun apiProfileDao(): ApiProfileDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        // 版本 1 的部分安装可能没有 api_profiles 表。只补建缺失表，保留视频历史与字幕缓存。
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `api_profiles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `baseUrl` TEXT NOT NULL,
                        `apiKey` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `region` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `charsUsed` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subtitle_cache ADD COLUMN videoKey TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE api_profiles ADD COLUMN credentialsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "translatetube.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
