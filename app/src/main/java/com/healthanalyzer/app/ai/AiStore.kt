package com.healthanalyzer.app.ai

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Wire protocol an endpoint speaks. Most vendors are OpenAI-compatible with their own base URL. */
enum class Protocol(val label: String) {
    OPENAI("OpenAI-compatible"),
    ANTHROPIC("Anthropic (Claude)"),
    GEMINI("Google Gemini"),
    CHATGPT("ChatGPT sign-in (Codex)"),
}

/**
 * Reasoning/thinking effort sent to models with extended thinking capabilities
 * (OpenAI o-series, Gemini 2.5/3, Claude 3.7, DeepSeek reasoner, etc.).
 */
enum class ThinkingLevel(val label: String, val apiValue: String?, val description: String) {
    DEFAULT("Default", null, "Model decides / unchanged"),
    OFF("Off", "off", "Disable thinking where supported"),
    LOW("Low", "low", "Fast, minimal reasoning tokens"),
    MEDIUM("Medium", "medium", "Balanced reasoning effort"),
    HIGH("High", "high", "Deep thinking, max reasoning tokens");
}

/** Ready-made endpoint settings; everything stays editable after picking one. */
data class ProviderPreset(
    val name: String,
    val protocol: Protocol,
    val baseUrl: String,
    val defaultModel: String = "",
    val needsKey: Boolean = true,
    val defaultThinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
)

val PROVIDER_PRESETS = listOf(
    ProviderPreset("ChatGPT sign-in (Codex)", Protocol.CHATGPT, CodexAuth.BASE_URL, needsKey = false),
    ProviderPreset("Anthropic", Protocol.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5"),
    ProviderPreset("OpenAI", Protocol.OPENAI, "https://api.openai.com/v1"),
    ProviderPreset("Google Gemini", Protocol.GEMINI, "https://generativelanguage.googleapis.com", "gemini-2.5-flash"),
    ProviderPreset("OpenRouter", Protocol.OPENAI, "https://openrouter.ai/api/v1"),
    ProviderPreset("DeepSeek", Protocol.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"),
    ProviderPreset("Qwen (DashScope)", Protocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    ProviderPreset("Moonshot / Kimi", Protocol.OPENAI, "https://api.moonshot.cn/v1"),
    ProviderPreset("Zhipu GLM", Protocol.OPENAI, "https://open.bigmodel.cn/api/paas/v4"),
    ProviderPreset("SiliconFlow", Protocol.OPENAI, "https://api.siliconflow.cn/v1"),
    ProviderPreset("Groq", Protocol.OPENAI, "https://api.groq.com/openai/v1"),
    ProviderPreset("xAI", Protocol.OPENAI, "https://api.x.ai/v1"),
    ProviderPreset("Mistral", Protocol.OPENAI, "https://api.mistral.ai/v1"),
    ProviderPreset("Ollama (LAN)", Protocol.OPENAI, "http://192.168.1.10:11434/v1", needsKey = false),
    ProviderPreset("LM Studio (LAN)", Protocol.OPENAI, "http://192.168.1.10:1234/v1", needsKey = false),
    ProviderPreset("Custom", Protocol.OPENAI, "https://"),
)

@Entity(tableName = "providers")
data class AiProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: Protocol,
    val baseUrl: String,
    /** API key encrypted with the Android Keystore (see [KeyCipher]); empty if none. */
    val encryptedKey: String,
    val model: String,
    val maxTokens: Int = 16000,
    /** Extra system-prompt text sent with every request through this provider (optional). */
    val extraSystem: String = "",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
)

@Entity(tableName = "prompts")
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String,
    /** User message with {{placeholders}}; see [PromptRenderer]. */
    val userTemplate: String,
    /** When true, a finished answer is saved as the athlete baseline. */
    @ColumnInfo(defaultValue = "0") val savesBaseline: Boolean = false,
)

@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdEpochMs: Long,
    val providerName: String,
    val model: String,
    val promptName: String,
    val runId: String?,
    val runLabel: String?,
    val systemText: String,
    val userText: String,
    val output: String,
    val error: String?,
)

/**
 * One chat conversation. The context flags choose what is added to the system prompt on every
 * message, so changing them mid-chat applies from the next message on.
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
    /** Provider used for the next reply; null = first provider. */
    val providerId: Long? = null,
    val includeProfile: Boolean = true,
    val includeBaseline: Boolean = true,
    /** Running stats (12 months), weekly totals (12 weeks) and runs of the last 28 days. */
    val includeTraining: Boolean = false,
    /** Saved analyses whose output is included, as comma-separated [AnalysisEntity] ids. */
    val analysisIds: String = "",
) {
    val analysisIdList: List<Long> get() = analysisIds.split(',').mapNotNull { it.trim().toLongOrNull() }
}

@Entity(tableName = "chat_messages", indices = [Index("chatId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    /** "user" or "assistant" (see [ChatTurn]). */
    val role: String,
    val text: String,
    val createdEpochMs: Long,
    /** Set on an assistant message whose reply failed or was stopped. */
    val error: String? = null,
    /** "Provider · model" that wrote an assistant message. */
    val model: String? = null,
)

@Dao
interface AiDao {
    // ---- Backup / restore: whole tables ----
    @Query("SELECT * FROM providers ORDER BY id") suspend fun allProviders(): List<AiProviderEntity>
    @Query("DELETE FROM providers") suspend fun deleteAllProviders()
    @Upsert suspend fun upsertProviders(p: List<AiProviderEntity>)
    @Query("SELECT * FROM prompts ORDER BY id") suspend fun allPrompts(): List<PromptTemplateEntity>
    @Query("DELETE FROM prompts") suspend fun deleteAllPrompts()
    @Upsert suspend fun upsertPrompts(p: List<PromptTemplateEntity>)
    @Query("SELECT * FROM analyses ORDER BY id") suspend fun allAnalyses(): List<AnalysisEntity>
    @Query("DELETE FROM analyses") suspend fun deleteAllAnalyses()
    @Upsert suspend fun upsertAnalyses(a: List<AnalysisEntity>)
    @Query("SELECT * FROM chats ORDER BY id") suspend fun allChats(): List<ChatEntity>
    @Query("DELETE FROM chats") suspend fun deleteAllChats()
    @Upsert suspend fun upsertChats(c: List<ChatEntity>)
    @Query("SELECT * FROM chat_messages ORDER BY id") suspend fun allChatMessages(): List<ChatMessageEntity>
    @Query("DELETE FROM chat_messages") suspend fun deleteAllChatMessages()
    @Upsert suspend fun upsertChatMessages(m: List<ChatMessageEntity>)

    // ---- Training plan ----
    @Query("SELECT * FROM plan_workouts ORDER BY date, id")
    fun observePlan(): Flow<List<PlanWorkoutEntity>>

    @Query("SELECT * FROM plan_workouts ORDER BY id") suspend fun allPlanWorkouts(): List<PlanWorkoutEntity>
    @Upsert suspend fun upsertPlanWorkouts(w: List<PlanWorkoutEntity>)
    @Query("DELETE FROM plan_workouts") suspend fun deleteAllPlanWorkouts()

    /** [from] is an ISO date; removes that day and everything after it. */
    @Query("DELETE FROM plan_workouts WHERE date >= :from") suspend fun deletePlanFrom(from: String)

    @Query("DELETE FROM plan_workouts WHERE id = :id") suspend fun deletePlanWorkout(id: Long)

    /** [from] and [to] are ISO dates, inclusive. */
    @Query("SELECT * FROM plan_workouts WHERE date >= :from AND date <= :to ORDER BY date, id")
    suspend fun planBetween(from: String, to: String): List<PlanWorkoutEntity>

    @Query("SELECT * FROM chats ORDER BY updatedEpochMs DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: Long): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChat(id: Long): ChatEntity?

    @Upsert
    suspend fun upsertChat(c: ChatEntity): Long

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: Long)

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY createdEpochMs, id")
    fun observeMessages(chatId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY createdEpochMs, id")
    suspend fun messages(chatId: Long): List<ChatMessageEntity>

    @Upsert
    suspend fun upsertMessage(m: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    suspend fun deleteMessages(chatId: Long)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("SELECT * FROM analyses WHERE id IN (:ids) ORDER BY createdEpochMs")
    suspend fun analysesByIds(ids: List<Long>): List<AnalysisEntity>

    @Query("SELECT * FROM providers ORDER BY id")
    fun observeProviders(): Flow<List<AiProviderEntity>>

    @Upsert
    suspend fun upsertProvider(p: AiProviderEntity): Long

    @Delete
    suspend fun deleteProvider(p: AiProviderEntity)

    @Query("SELECT * FROM prompts ORDER BY id")
    fun observePrompts(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT COUNT(*) FROM prompts")
    suspend fun promptCount(): Int

    @Query("SELECT name FROM prompts")
    suspend fun promptNames(): List<String>

    @Query("SELECT COUNT(*) FROM prompts WHERE savesBaseline = 1")
    suspend fun baselinePromptCount(): Int

    @Upsert
    suspend fun upsertPrompt(p: PromptTemplateEntity): Long

    @Insert
    suspend fun insertPrompts(p: List<PromptTemplateEntity>)

    @Delete
    suspend fun deletePrompt(p: PromptTemplateEntity)

    @Query("SELECT * FROM analyses ORDER BY createdEpochMs DESC")
    fun observeAnalyses(): Flow<List<AnalysisEntity>>

    @Insert
    suspend fun insertAnalysis(a: AnalysisEntity): Long

    @Delete
    suspend fun deleteAnalysis(a: AnalysisEntity)
}

/** Kept apart from the run database so AI settings never force a migration of synced data. */
@Database(
    entities = [
        AiProviderEntity::class, PromptTemplateEntity::class, AnalysisEntity::class,
        ChatEntity::class, ChatMessageEntity::class, PlanWorkoutEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AiDatabase : RoomDatabase() {
    abstract fun aiDao(): AiDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prompts ADD COLUMN savesBaseline INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: chat conversations. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `createdEpochMs` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, " +
                        "`providerId` INTEGER, `includeProfile` INTEGER NOT NULL, `includeBaseline` INTEGER NOT NULL, " +
                        "`includeTraining` INTEGER NOT NULL, `analysisIds` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`createdEpochMs` INTEGER NOT NULL, `error` TEXT, `model` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_chatId` ON `chat_messages` (`chatId`)")
            }
        }

        /** v4: thinking level for providers. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `thinkingLevel` TEXT NOT NULL DEFAULT 'DEFAULT'")
            }
        }

        /** v5: training plan calendar. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_workouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `distanceKm` REAL, " +
                        "`durationMin` INTEGER, `target` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdEpochMs` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_workouts_date` ON `plan_workouts` (`date`)")
            }
        }

        fun create(context: Context): AiDatabase =
            Room.databaseBuilder(context, AiDatabase::class.java, "ai.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
