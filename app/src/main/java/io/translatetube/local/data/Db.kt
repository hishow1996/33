package io.translatetube.local.data

import android.content.Context
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
)

@Dao
interface SubtitleCacheDao {
    @Query("SELECT * FROM subtitle_cache WHERE cacheKey = :k")
    suspend fun get(k: String): SubtitleCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: SubtitleCacheEntity)

    @Query("DELETE FROM subtitle_cache")
    suspend fun clear()
}

/** 用户配置的云翻译 API */
@Entity(tableName = "api_profiles")
data class ApiProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,                       // OPENAI / GEMINI / GCLOUD / AZURE
    val baseUrl: String,                    // OPENAI: https://api.xx.com/v1 类
    val apiKey: String,                     // AES 加密存储
    val model: String,                      // 选定的翻译模型（传统引擎为空）
    val region: String,                     // Azure region
    val enabled: Boolean = true,
    val charsUsed: Long = 0,                // 用量统计（字符）
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
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun subtitleCacheDao(): SubtitleCacheDao
    abstract fun apiProfileDao(): ApiProfileDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "translatetube.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
