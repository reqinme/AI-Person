package qinme.aiperson

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 角色卡：每个人格独立一张，独立主题色、接口、头像与背景。 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val gender: String,
    val persona: String,
    val accentColor: Int,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val dislikes: String = "",
    @ColumnInfo(defaultValue = "''") val avatarPath: String = "",
    @ColumnInfo(defaultValue = "''") val backgroundPath: String = "",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val strongAlertAt: Long = 0L,
    @ColumnInfo(defaultValue = "''") val apiBaseUrl: String = "",
    @ColumnInfo(defaultValue = "''") val apiModel: String = "",
    @ColumnInfo(defaultValue = "1") val vision: Boolean = true
)

/** 聊天记录。visibleAt > 0 表示延迟消息，到点才显示。role 可为 user / assistant / system / note。 */
@Entity(tableName = "messages", indices = [Index("characterId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val proactive: Boolean = false,
    @ColumnInfo(defaultValue = "''") val imagePath: String = "",
    @ColumnInfo(defaultValue = "''") val imageCaption: String = "",
    @ColumnInfo(defaultValue = "0") val visibleAt: Long = 0L
)

/** 长期记忆。tier 为 ACTIVE / FADED，权重衰减到阈值后再删除。 */
@Entity(tableName = "memories", indices = [Index("characterId")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val content: String,
    val importance: Int = 3,
    val tags: String = "",
    val weight: Float = 3f,
    val tier: String = "ACTIVE",
    val pinned: Boolean = false,
    val createdAt: Long,
    val lastRecalledAt: Long
)

/** 关系状态：亲密度 0-100，阶段由数值推导。 */
@Entity(tableName = "relationship")
data class RelationshipEntity(
    @PrimaryKey val characterId: String,
    val intimacy: Int = 0,
    val lastChatAt: Long = 0
)

/** 人格状态：当前值 + 用户设定的偏好值，演化只做软引导。 */
@Entity(tableName = "personality")
data class PersonalityEntity(
    @PrimaryKey val characterId: String,
    val warmth: Int = 50,
    val liveliness: Int = 50,
    val initiative: Int = 40,
    val independence: Int = 50,
    val clinginess: Int = 30,
    val prefWarmth: Int = 50,
    val prefLiveliness: Int = 50,
    val prefInitiative: Int = 40,
    val prefIndependence: Int = 50,
    val prefClinginess: Int = 30
)

/** 当前心情与耐心，影响语气、回不回、回多快。 */
@Entity(tableName = "mood")
data class MoodEntity(
    @PrimaryKey val characterId: String,
    val mood: Int = 55,
    val patience: Int = 55,
    val updatedAt: Long = 0L
)

fun intimacyStage(intimacy: Int): String = when {
    intimacy < 20 -> "陌生"
    intimacy < 50 -> "熟悉"
    intimacy < 80 -> "亲密"
    else -> "依恋"
}

/** 新建角色留空底线时写入的三条默认底线。 */
const val DEFAULT_DISLIKES = "被欺骗、被敷衍\n被当成工具使唤\n被无视"

/**
 * 关系增益：亲密度 50 以下为 0，50 → 100 平滑加到 20。
 * 只作用在温柔、粘人、主动三项，活泼和独立保持基线。
 */
fun relationGain(intimacy: Int): Int {
    if (intimacy <= 50) return 0
    return (((intimacy - 50) / 50f) * 20f).toInt().coerceIn(0, 20)
}

/** 加上关系增益之后，她此刻实际呈现的人格。 */
fun effectivePersonality(personality: PersonalityEntity, intimacy: Int): PersonalityEntity {
    val gain = relationGain(intimacy)
    if (gain == 0) return personality
    return personality.copy(
        warmth = (personality.warmth + gain).coerceIn(0, 100),
        initiative = (personality.initiative + gain).coerceIn(0, 100),
        clinginess = (personality.clinginess + gain).coerceIn(0, 100)
    )
}

/** 全局搜索结果：消息 + 角色名。 */
data class MessageHit(
    val id: Long,
    val characterId: String,
    val characterName: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    /** 置顶优先，其次按最近聊天时间。 */
    @Query(
        """
        SELECT c.* FROM characters c LEFT JOIN relationship r ON r.characterId = c.id
        ORDER BY c.pinned DESC, COALESCE(r.lastChatAt, c.createdAt) DESC
        """
    )
    fun observeOrdered(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    fun observe(id: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Query("SELECT * FROM characters WHERE name LIKE '%' || :q || '%' ORDER BY pinned DESC, createdAt DESC")
    suspend fun search(q: String): List<CharacterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(character: CharacterEntity)

    @Query("SELECT * FROM characters")
    suspend fun all(): List<CharacterEntity>

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM characters")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt ASC, id ASC")
    fun observeAll(characterId: String): Flow<List<MessageEntity>>

    /** 列表页预览：该角色最新一条消息。 */
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun observeLast(characterId: String): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM messages WHERE characterId = :characterId AND role IN ('user','assistant')")
    suspend fun countDialogue(characterId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(characterId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE characterId = :characterId")
    suspend fun all(characterId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE characterId = :characterId AND id > :afterId")
    suspend fun countAfter(characterId: String, afterId: Long): Int

    @Query("SELECT MAX(id) FROM messages WHERE characterId = :characterId")
    suspend fun latestId(characterId: String): Long?

    @Query("UPDATE messages SET imageCaption = :caption WHERE id = :id")
    suspend fun setCaption(id: Long, caption: String)

    @Query(
        "SELECT * FROM messages WHERE characterId = :characterId AND content LIKE '%' || :q || '%' ORDER BY createdAt DESC"
    )
    suspend fun search(characterId: String, q: String): List<MessageEntity>

    @Query(
        """
        SELECT m.id AS id, m.characterId AS characterId, c.name AS characterName,
               m.role AS role, m.content AS content, m.createdAt AS createdAt
        FROM messages m INNER JOIN characters c ON c.id = m.characterId
        WHERE m.content LIKE '%' || :q || '%'
        ORDER BY m.createdAt DESC
        """
    )
    suspend fun searchAll(q: String): List<MessageHit>

    @Query("DELETE FROM messages WHERE characterId = :characterId")
    suspend fun deleteAll(characterId: String)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY pinned DESC, weight DESC, lastRecalledAt DESC LIMIT :limit")
    suspend fun recall(characterId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY pinned DESC, weight DESC, lastRecalledAt DESC")
    fun observeAll(characterId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE characterId = :characterId")
    suspend fun list(characterId: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Query("UPDATE memories SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE memories SET weight = :weight, tier = :tier WHERE id = :id")
    suspend fun updateWeight(id: Long, weight: Float, tier: String)

    @Query("UPDATE memories SET importance = :importance, weight = :weight, lastRecalledAt = :recalledAt WHERE id = :id")
    suspend fun reinforce(id: Long, importance: Int, weight: Float, recalledAt: Long)

    @Query("UPDATE memories SET content = :content, importance = :importance, weight = :weight, tier = 'ACTIVE' WHERE id = :id")
    suspend fun edit(id: Long, content: String, importance: Int, weight: Float)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM memories")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId AND content = :content")
    suspend fun countSame(characterId: String, content: String): Int

    @Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId AND tags LIKE '%' || :tag || '%'")
    suspend fun countTag(characterId: String, tag: String): Int

    @Query("DELETE FROM memories WHERE weight < :threshold AND lastRecalledAt < :before AND pinned = 0")
    suspend fun deleteStale(threshold: Float, before: Long)

    @Query("DELETE FROM memories WHERE characterId = :characterId")
    suspend fun deleteAll(characterId: String)

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface RelationshipDao {
    @Query("SELECT * FROM relationship WHERE characterId = :characterId")
    fun observe(characterId: String): Flow<RelationshipEntity?>

    @Query("SELECT * FROM relationship WHERE characterId = :characterId")
    suspend fun get(characterId: String): RelationshipEntity?

    @Query("SELECT * FROM relationship")
    suspend fun all(): List<RelationshipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: RelationshipEntity)

    @Query("DELETE FROM relationship WHERE characterId = :characterId")
    suspend fun delete(characterId: String)

    @Query("DELETE FROM relationship")
    suspend fun clear()
}

@Dao
interface PersonalityDao {
    @Query("SELECT * FROM personality WHERE characterId = :characterId")
    suspend fun get(characterId: String): PersonalityEntity?

    @Query("SELECT * FROM personality")
    suspend fun all(): List<PersonalityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(personality: PersonalityEntity)

    @Query("DELETE FROM personality WHERE characterId = :characterId")
    suspend fun delete(characterId: String)

    @Query("DELETE FROM personality")
    suspend fun clear()
}

@Dao
interface MoodDao {
    @Query("SELECT * FROM mood WHERE characterId = :characterId")
    fun observe(characterId: String): Flow<MoodEntity?>

    @Query("SELECT * FROM mood WHERE characterId = :characterId")
    suspend fun get(characterId: String): MoodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mood: MoodEntity)

    @Query("DELETE FROM mood WHERE characterId = :characterId")
    suspend fun delete(characterId: String)

    @Query("DELETE FROM mood")
    suspend fun clear()
}

@Database(
    entities = [
        CharacterEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        RelationshipEntity::class,
        PersonalityEntity::class,
        MoodEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun personalityDao(): PersonalityDao
    abstract fun moodDao(): MoodDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** 1 -> 2：新增头像、背景、置顶、强提醒、接口、图片、可见时间与心情表，旧数据保留。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN dislikes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN avatarPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN backgroundPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN strongAlertAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN apiBaseUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN apiModel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN vision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageCaption TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN visibleAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_characterId ON messages(characterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_characterId ON memories(characterId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mood (" +
                        "characterId TEXT NOT NULL, mood INTEGER NOT NULL, " +
                        "patience INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(characterId))"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ai_person.db"
            ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
