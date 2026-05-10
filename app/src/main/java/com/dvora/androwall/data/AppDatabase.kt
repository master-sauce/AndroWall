package com.dvora.androwall.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM app_configs ORDER BY appName ASC")
    fun getAllAppConfigs(): Flow<List<AppConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppConfig(config: AppConfig)

    // Returns app-specific AND global blocks for a given package
    @Query("SELECT * FROM blocked_domains WHERE packageName = :packageName OR packageName IS NULL")
    fun getBlockedDomains(packageName: String?): Flow<List<BlockedDomain>>

    // Only the per-app entries (shown in AppDetailScreen)
    @Query("SELECT * FROM blocked_domains WHERE packageName = :packageName")
    fun getBlockedDomainsForApp(packageName: String): Flow<List<BlockedDomain>>

    // Only globally-blocked entries (packageName IS NULL)
    @Query("SELECT * FROM blocked_domains WHERE packageName IS NULL")
    fun getGlobalBlockedDomains(): Flow<List<BlockedDomain>>

    // ALL blocked domains — used by the VPN service cache
    @Query("SELECT * FROM blocked_domains")
    fun getAllBlockedDomains(): Flow<List<BlockedDomain>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedDomain(domain: BlockedDomain)

    @Delete
    suspend fun deleteBlockedDomain(domain: BlockedDomain)

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
    entities = [AppConfig::class, BlockedDomain::class, ConnectionLog::class],
    version = 1
)
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
                ).build().also { INSTANCE = it }
            }
    }
}