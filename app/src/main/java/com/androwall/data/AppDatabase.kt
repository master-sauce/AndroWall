package com.androwall.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query("SELECT * FROM app_configs ORDER BY appName ASC")
    fun getAllAppConfigs(): Flow<List<AppConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppConfig(config: AppConfig)

    // ── Legacy blocked_domains ────────────────────────────────────────────────
    @Query("SELECT * FROM blocked_domains WHERE packageName = :pkg OR packageName IS NULL")
    fun getBlockedDomains(pkg: String?): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domains WHERE packageName IS NULL")
    fun getGlobalBlockedDomains(): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domains")
    fun getAllBlockedDomains(): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM blocked_domains WHERE packageName = :pkg")
    fun getBlockedDomainsForApp(pkg: String): Flow<List<BlockedDomain>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedDomain(domain: BlockedDomain)

    @Delete
    suspend fun deleteBlockedDomain(domain: BlockedDomain)

    // ── Filter rules ──────────────────────────────────────────────────────────
    @Query("SELECT * FROM filter_rules WHERE packageName IS NULL ORDER BY action ASC")
    fun getGlobalRules(): Flow<List<FilterRule>>

    @Query("SELECT * FROM filter_rules WHERE packageName = :pkg ORDER BY action ASC")
    fun getAppRules(pkg: String): Flow<List<FilterRule>>

    /** App-specific rules first, then global — used by VPN engine cache. */
    @Query("""
        SELECT * FROM filter_rules
        ORDER BY CASE WHEN packageName IS NULL THEN 1 ELSE 0 END ASC, id ASC
    """)
    fun getAllRules(): Flow<List<FilterRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FilterRule)

    @Update
    suspend fun updateRule(rule: FilterRule)

    @Delete
    suspend fun deleteRule(rule: FilterRule)

    @Query("UPDATE filter_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Int, enabled: Boolean)

    // ── Connection logs ───────────────────────────────────────────────────────
    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT 300")
    fun getRecentLogs(): Flow<List<ConnectionLog>>

    @Query("SELECT * FROM connection_logs WHERE packageName = :pkg ORDER BY timestamp DESC LIMIT 100")
    fun getLogsForApp(pkg: String): Flow<List<ConnectionLog>>

    @Insert
    suspend fun insertLog(log: ConnectionLog)

    @Query("DELETE FROM connection_logs")
    suspend fun clearLogs()
}

@Database(
    entities = [AppConfig::class, BlockedDomain::class, ConnectionLog::class, FilterRule::class],
    version  = 3
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "androwall_db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

class RoomConverters {
    @TypeConverter fun matchTypeToStr(v: MatchType): String   = v.name
    @TypeConverter fun strToMatchType(v: String):   MatchType =
        runCatching { MatchType.valueOf(v) }.getOrDefault(MatchType.SUBDOMAIN)

    @TypeConverter fun actionToStr(v: RuleAction):   String     = v.name
    @TypeConverter fun strToAction(v: String):       RuleAction = RuleAction.valueOf(v)

    @TypeConverter fun scopeToStr(v: RuleScope):     String    = v.name
    @TypeConverter fun strToScope(v: String):        RuleScope =
        runCatching { RuleScope.valueOf(v) }.getOrDefault(RuleScope.ANY)

    @TypeConverter fun connTypeToStr(v: ConnectionType): String         = v.name
    @TypeConverter fun strToConnType(v: String):         ConnectionType =
        runCatching { ConnectionType.valueOf(v) }.getOrDefault(ConnectionType.DNS)
}