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
    @ColumnInfo(defaultValue = "1") val vision: Boolean = true,
    /** 她后来对他改口的称呼；空字符串表示还叫「你」。 */
    @ColumnInfo(defaultValue = "''") val nickname: String = "",
    /** 她的身份/在做什么，比如「插画师，独居」。 */
    @ColumnInfo(defaultValue = "''") val identity: String = "",
    /** 她自己的来历，会影响她怎么看待事情。 */
    @ColumnInfo(defaultValue = "''") val background: String = "",
    /** 口头禅或固定说法，几句话就够。 */
    @ColumnInfo(defaultValue = "''") val catchphrase: String = "",
    /** 玩梗程度 0-100；越高越爱用网络梗，但不是每句都用。 */
    @ColumnInfo(defaultValue = "50") val memeLevel: Int = 50
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

/** 人格状态：当前值 + 底色（成长前的样子）+ 用户设定的偏好值。 */
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
    val prefClinginess: Int = 30,
    /** 底色原型名；旧角色迁移后是「自定义」。 */
    @ColumnInfo(defaultValue = "'自定义'") val temperament: String = "自定义",
    @ColumnInfo(defaultValue = "50") val baseWarmth: Int = 50,
    @ColumnInfo(defaultValue = "50") val baseLiveliness: Int = 50,
    @ColumnInfo(defaultValue = "40") val baseInitiative: Int = 40,
    @ColumnInfo(defaultValue = "50") val baseIndependence: Int = 50,
    @ColumnInfo(defaultValue = "30") val baseClinginess: Int = 30,
    /** 这些年她变成什么样的叙述，换行分隔，只留最近几条。 */
    @ColumnInfo(defaultValue = "''") val personaChanges: String = "",
    @ColumnInfo(defaultValue = "0") val lastPersonaChangeAt: Long = 0L
)

/** 人设变化记录：一句她变成什么样 + 原因 + 时间。 */
@Entity(tableName = "persona_history", indices = [Index("characterId")])
data class PersonaChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: String,
    val change: String,
    @ColumnInfo(defaultValue = "''") val reason: String = "",
    val createdAt: Long
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
 * 每个维度按自己的底色缩放：底色越接近，越容易因为关系而显出来。
 */
fun relationGain(intimacy: Int, base: Int): Int {
    if (intimacy <= 50) return 0
    val affinity = 0.4f + (base.coerceIn(0, 100) / 100f) * 0.6f
    return ((((intimacy - 50) / 50f) * 20f) * affinity).toInt().coerceIn(0, 20)
}

/** 三个会被关系放大的维度各自涨多少；独立会用来自我抵抗粘人的涨幅。 */
data class RelationGains(val warmth: Int, val initiative: Int, val clinginess: Int)

fun relationGains(personality: PersonalityEntity, intimacy: Int): RelationGains {
    val warmth = relationGain(intimacy, personality.baseWarmth)
    val initiative = relationGain(intimacy, personality.baseInitiative)
    val rawCling = relationGain(intimacy, personality.baseClinginess)
    val resistance = 1f - personality.baseIndependence.coerceIn(0, 100) / 200f
    return RelationGains(
        warmth = warmth,
        initiative = initiative,
        clinginess = (rawCling * resistance).toInt().coerceIn(0, 20)
    )
}

/** 加上关系增益之后，她此刻实际呈现的人格。 */
fun effectivePersonality(personality: PersonalityEntity, intimacy: Int): PersonalityEntity {
    val gains = relationGains(personality, intimacy)
    if (gains.warmth == 0 && gains.initiative == 0 && gains.clinginess == 0) return personality
    return personality.copy(
        warmth = (personality.warmth + gains.warmth).coerceIn(0, 100),
        initiative = (personality.initiative + gains.initiative).coerceIn(0, 100),
        clinginess = (personality.clinginess + gains.clinginess).coerceIn(0, 100)
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

    /** 列表页预览：取最近几条，由 UI 跳过还没到时间的延迟消息和空 note。 */
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(characterId: String, limit: Int): Flow<List<MessageEntity>>

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

    @Query(
        "SELECT COUNT(*) FROM messages WHERE characterId = :characterId AND createdAt >= :since AND role IN ('user','assistant')"
    )
    suspend fun countDialogueSince(characterId: String, since: Long): Int

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

    @Query("DELETE FROM messages WHERE characterId = :characterId AND role = 'assistant' AND visibleAt > :now")
    suspend fun dropPendingReplies(characterId: String, now: Long)

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

    /** 召回即刷新：这一轮被想起的记忆重新回到满权重，从当下重新开始衰减。 */
    @Query("UPDATE memories SET weight = importance, tier = 'ACTIVE', lastRecalledAt = :at WHERE id IN (:ids)")
    suspend fun touchRecalled(ids: List<Long>, at: Long)

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

    @Query("SELECT * FROM personality WHERE characterId = :characterId")
    fun observe(characterId: String): Flow<PersonalityEntity?>

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
interface PersonaHistoryDao {
    @Query("SELECT * FROM persona_history WHERE characterId = :characterId ORDER BY createdAt DESC")
    fun observe(characterId: String): Flow<List<PersonaChangeEntity>>

    @Query("SELECT * FROM persona_history WHERE characterId = :characterId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(characterId: String, limit: Int): List<PersonaChangeEntity>

    @Query("SELECT COUNT(*) FROM persona_history WHERE characterId = :characterId AND createdAt >= :since")
    suspend fun countSince(characterId: String, since: Long): Int

    @Insert
    suspend fun insert(entry: PersonaChangeEntity): Long

    @Query("SELECT * FROM persona_history")
    suspend fun all(): List<PersonaChangeEntity>

    @Query("DELETE FROM persona_history WHERE characterId = :characterId")
    suspend fun deleteAll(characterId: String)

    @Query("DELETE FROM persona_history")
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
        PersonaChangeEntity::class,
        MoodEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun personalityDao(): PersonalityDao
    abstract fun personaHistoryDao(): PersonaHistoryDao
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

        /**
         * 2 -> 3：人格分底色与成长值，新增人设变化记录和称呼。
         * 旧角色的底色取当前值（标成「自定义」），偏好夹回底色 ±15。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN nickname TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE personality ADD COLUMN temperament TEXT NOT NULL DEFAULT '自定义'")
                db.execSQL("ALTER TABLE personality ADD COLUMN baseWarmth INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE personality ADD COLUMN baseLiveliness INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE personality ADD COLUMN baseInitiative INTEGER NOT NULL DEFAULT 40")
                db.execSQL("ALTER TABLE personality ADD COLUMN baseIndependence INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE personality ADD COLUMN baseClinginess INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE personality ADD COLUMN personaChanges TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE personality ADD COLUMN lastPersonaChangeAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE personality SET baseWarmth = warmth, baseLiveliness = liveliness, " +
                    "baseInitiative = initiative, baseIndependence = independence, baseClinginess = clinginess")
                db.execSQL("UPDATE personality SET " +
                    "prefWarmth = MIN(MAX(prefWarmth, baseWarmth - 15), baseWarmth + 15), " +
                    "prefLiveliness = MIN(MAX(prefLiveliness, baseLiveliness - 15), baseLiveliness + 15), " +
                    "prefInitiative = MIN(MAX(prefInitiative, baseInitiative - 15), baseInitiative + 15), " +
                    "prefIndependence = MIN(MAX(prefIndependence, baseIndependence - 15), baseIndependence + 15), " +
                    "prefClinginess = MIN(MAX(prefClinginess, baseClinginess - 15), baseClinginess + 15)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS persona_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "characterId TEXT NOT NULL, change TEXT NOT NULL, " +
                        "reason TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_persona_history_characterId " +
                        "ON persona_history(characterId)"
                )
            }
        }

        /** 3 -> 4：角色可以自己写身份、来历、口头禅，并调玩梗程度。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN identity TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN background TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN catchphrase TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE characters ADD COLUMN memeLevel INTEGER NOT NULL DEFAULT 50")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ai_person.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
