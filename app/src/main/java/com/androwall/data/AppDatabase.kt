package com.androwall.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun matchTypeToString(v: MatchType): String = v.name
    @TypeConverter fun stringToMatchType(v: String): MatchType = MatchType.valueOf(v)
    @TypeConverter fun ruleActionToString(v: RuleAction): String = v.name
    @TypeConverter fun stringToRuleAction(v: String): RuleAction = RuleAction.valueOf(v)
}

@Dao
interface AppDao {
    // ── App configs ──────────────────────────────────────────────────────────
    @Query("SELECT * FROM app_configs ORDER BY appName ASC")
    fun getAllAppConfigs(): Flow<List<AppConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppConfig(config: AppConfig)

    // ── Filter rules ─────────────────────────────────────────────────────────
    @Query("SELECT * FROM filter_rules ORDER BY action ASC, pattern ASC")
    fun getAllRules(): Flow<List<FilterRule>>

    @Query("SELECT * FROM filter_rules WHERE packageName IS NULL ORDER BY action ASC, pattern ASC")
    fun getGlobalRules(): Flow<List<FilterRule>>

    @Query("SELECT * FROM filter_rules WHERE packageName = :pkg ORDER BY action ASC, pattern ASC")
    fun getAppRules(pkg: String): Flow<List<FilterRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FilterRule)

    @Delete
    suspend fun deleteRule(rule: FilterRule)

    @Query("UPDATE filter_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Int, enabled: Boolean)

    // ── Connection logs ──────────────────────────────────────────────────────
    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<ConnectionLog>>

    @Insert
    suspend fun insertLog(log: ConnectionLog)

    @Query("DELETE FROM connection_logs")
    suspend fun clearLogs()
}

@TypeConverters(Converters::class)
@Database(
    entities = [AppConfig::class, FilterRule::class, ConnectionLog::class],
    version = 2  // bumped — fallbackToDestructiveMigration wipes old data on schema change
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "androwall_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}