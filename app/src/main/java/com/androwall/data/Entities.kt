package com.androwall.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_configs")
data class AppConfig(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isFilteringEnabled: Boolean = true
)

enum class MatchType {
    EXACT,      // "ads.com"   → only "ads.com"
    SUBDOMAIN,  // "ads.com"   → "ads.com" and any subdomain
    PREFIX,     // "ads"       → "ads.example.com"
    SUFFIX,     // ".ru"       → "evil.ru"
    CONTAINS    // "tracker"   → "ad-tracker.com"
}

enum class RuleAction { BLOCK, ALLOW }

/** BLACKLIST = block matched, allow rest.  WHITELIST = allow matched, block rest. */
enum class FilterMode { BLACKLIST, WHITELIST }

@Entity(tableName = "filter_rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String?,
    val pattern: String,
    val matchType: MatchType = MatchType.SUBDOMAIN,
    val action: RuleAction = RuleAction.BLOCK,
    val isEnabled: Boolean = true
)

@Entity(tableName = "connection_logs")
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean
)