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
}

@Database(entities = [RunEntity::class, RunDetailEntity::class], version = 3, exportSchema = false)
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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "health_analyzer.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
