package com.androwall.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ── App configs ───────────────────────────────────────────────────────────
    @Query("SELECT * FROM app_configs ORDER BY appName ASC")
    fun getAllAppConfigs(): Flow<List<AppConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppConfig(config: AppConfig)

    // ── Blocked domains (legacy fallback) ─────────────────────────────────────
    @Query("SELECT * FROM blocked_domains WHERE packageName = :packageName OR packageName IS NULL")
    fun getBlockedDomains(packageName: String?): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domains WHERE packageName = :packageName")
    fun getBlockedDomainsForApp(packageName: String): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domains WHERE packageName IS NULL")
    fun getGlobalBlockedDomains(): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domains")
    fun getAllBlockedDomains(): Flow<List<BlockedDomain>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedDomain(domain: BlockedDomain)

    @Delete
    suspend fun deleteBlockedDomain(domain: BlockedDomain)

    // ── Filter rules ──────────────────────────────────────────────────────────
    @Query("SELECT * FROM filter_rules WHERE packageName IS NULL ORDER BY action ASC")
    fun getGlobalRules(): Flow<List<FilterRule>>

    @Query("SELECT * FROM filter_rules WHERE packageName = :packageName ORDER BY action ASC")
    fun getAppRules(packageName: String): Flow<List<FilterRule>>

    @Query("""
    SELECT * FROM filter_rules 
    ORDER BY 
        CASE WHEN packageName IS NULL THEN 1 ELSE 0 END ASC,
        id ASC
""")
    fun getAllRules(): Flow<List<FilterRule>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FilterRule)

    @Delete
    suspend fun deleteRule(rule: FilterRule)

    @Query("UPDATE filter_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Int, enabled: Boolean)

    // ── Connection logs ───────────────────────────────────────────────────────
    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<ConnectionLog>>

    @Query("SELECT * FROM connection_logs WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT 50")
    fun getLogsForApp(packageName: String): Flow<List<ConnectionLog>>

    @Insert
    suspend fun insertLog(log: ConnectionLog)

    @Query("DELETE FROM connection_logs")
    suspend fun clearLogs()
}

@Database(
    entities = [AppConfig::class, BlockedDomain::class, ConnectionLog::class, FilterRule::class],
    version = 2
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "androwall_db"
                )
                    .fallbackToDestructiveMigration()   // drops & recreates on schema bump
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

/** Room type converters for enum columns. */
class RoomConverters {
    @TypeConverter fun matchTypeToString(v: MatchType): String = v.name
    @TypeConverter fun stringToMatchType(v: String): MatchType = MatchType.valueOf(v)
    @TypeConverter fun ruleActionToString(v: RuleAction): String = v.name
    @TypeConverter fun stringToRuleAction(v: String): RuleAction = RuleAction.valueOf(v)
}