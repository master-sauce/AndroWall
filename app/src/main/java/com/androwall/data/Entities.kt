package com.androwall.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_configs")
data class AppConfig(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isFilteringEnabled: Boolean = true
)

@Entity(tableName = "blocked_domains")
data class BlockedDomain(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String?, // null if global
    val domain: String
)

@Entity(tableName = "connection_logs")
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean
)
