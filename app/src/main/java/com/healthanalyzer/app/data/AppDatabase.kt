package com.healthanalyzer.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {
    @Query("SELECT * FROM runs ORDER BY startEpochMs DESC")
    fun observeRuns(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun getRun(id: String): RunEntity?

    @Query("SELECT * FROM run_details WHERE runId = :id")
    suspend fun getDetail(id: String): RunDetailEntity?

    @Query("SELECT * FROM runs WHERE startEpochMs >= :sinceEpochMs ORDER BY startEpochMs")
    suspend fun runsSince(sinceEpochMs: Long): List<RunEntity>

    @Query("SELECT * FROM runs ORDER BY startEpochMs DESC LIMIT 1")
    suspend fun latestRun(): RunEntity?

    @Query("SELECT MAX(maxHeartRate) FROM runs")
    suspend fun highestHeartRate(): Float?

    @Query("SELECT MAX(startEpochMs) FROM runs WHERE source IS NULL OR source = 'SAMSUNG'")
    suspend fun latestStart(): Long?

    @Query("SELECT * FROM runs WHERE startEpochMs BETWEEN :fromMs AND :toMs")
    suspend fun runsStartingBetween(fromMs: Long, toMs: Long): List<RunEntity>

    @Query("SELECT * FROM runs WHERE id IN (:ids)")
    suspend fun runsByIds(ids: List<String>): List<RunEntity>

    @Query("SELECT * FROM runs ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun runsPage(limit: Int, offset: Int): List<RunEntity>

    @Query("SELECT * FROM run_details ORDER BY runId LIMIT :limit OFFSET :offset")
    suspend fun detailsPage(limit: Int, offset: Int): List<RunDetailEntity>

    @Query("SELECT MAX(startEpochMs) FROM runs WHERE source = 'GARMIN_FIT'")
    suspend fun latestGarminStart(): Long?

    @Query("SELECT sourceDetail FROM runs WHERE source = 'GARMIN_FIT' AND sourceDetail IS NOT NULL")
    suspend fun garminSourceDetails(): List<String>

    @Query("SELECT COUNT(*) FROM runs")
    suspend fun runCount(): Int

    @Query("DELETE FROM runs")
    suspend fun deleteAllRuns()

    @Query("DELETE FROM run_details")
    suspend fun deleteAllDetails()

    @Upsert
    suspend fun upsertRuns(runs: List<RunEntity>)

    @Upsert
    suspend fun upsertDetails(details: List<RunDetailEntity>)

    // ---- Recovery and non-running workouts ----

    /** [from] and [to] are ISO dates, inclusive. */
    @Query("SELECT * FROM daily_recovery WHERE date >= :from AND date <= :to ORDER BY date")
    suspend fun recoveryBetween(from: String, to: String): List<DailyRecoveryEntity>

    @Query("SELECT MAX(date) FROM daily_recovery")
    suspend fun latestRecoveryDate(): String?

    @Upsert
    suspend fun upsertRecovery(days: List<DailyRecoveryEntity>)

    @Query("DELETE FROM daily_recovery")
    suspend fun deleteAllRecovery()

    @Query("SELECT * FROM other_workouts WHERE startEpochMs >= :sinceEpochMs ORDER BY startEpochMs")
    suspend fun otherWorkoutsSince(sinceEpochMs: Long): List<OtherWorkoutEntity>

    @Upsert
    suspend fun upsertOtherWorkouts(workouts: List<OtherWorkoutEntity>)

    @Query("DELETE FROM other_workouts")
    suspend fun deleteAllOtherWorkouts()
}

@Database(
    entities = [RunEntity::class, RunDetailEntity::class, DailyRecoveryEntity::class, OtherWorkoutEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        /** v2: data source + Garmin running dynamics. Existing rows are Samsung runs. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "source TEXT", "sourceDetail TEXT", "groundContactMs REAL", "verticalOscillationMm REAL",
                    "verticalRatioPct REAL", "stepLengthMm REAL", "groundContactBalancePct REAL",
                    "aerobicTrainingEffect REAL", "anaerobicTrainingEffect REAL",
                ).forEach { db.execSQL("ALTER TABLE runs ADD COLUMN $it") }
            }
        }

        /** v3: running dynamics and sweat loss from the Samsung Health data export. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("flightTimeMs REAL", "asymmetryPct REAL", "regularityPct REAL", "stiffnessKnM REAL", "sweatLossMl REAL")
                    .forEach { db.execSQL("ALTER TABLE runs ADD COLUMN $it") }
            }
        }

        /** v4: daily recovery (sleep, energy score, resting HR) and non-running workouts. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_recovery` (`date` TEXT NOT NULL, `sleepScore` INTEGER, `sleepMinutes` INTEGER, " +
                        "`deepMinutes` INTEGER, `remMinutes` INTEGER, `awakeMinutes` INTEGER, `bedtimeEpochMs` INTEGER, " +
                        "`wakeEpochMs` INTEGER, `energyScore` REAL, `restingHr` INTEGER, PRIMARY KEY(`date`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `other_workouts` (`id` TEXT NOT NULL, `exerciseType` TEXT NOT NULL, `title` TEXT, " +
                        "`startEpochMs` INTEGER NOT NULL, `endEpochMs` INTEGER NOT NULL, `zoneOffsetSec` INTEGER, " +
                        "`durationSec` INTEGER NOT NULL, `distanceM` REAL, `calories` REAL, `meanHeartRate` REAL, " +
                        "`maxHeartRate` REAL, `altitudeGain` REAL, `sourceAppId` TEXT, `hrSeconds` TEXT, PRIMARY KEY(`id`))",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "health_analyzer.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
