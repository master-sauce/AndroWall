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
    val packageName: String?,
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

// ── Rule system ───────────────────────────────────────────────────────────────

enum class FilterMode { BLACKLIST, WHITELIST }
enum class RuleAction  { BLOCK, ALLOW }

enum class MatchType {
    EXACT, SUBDOMAIN, CONTAINS, PREFIX, SUFFIX;

    fun displayName() = when (this) {
        EXACT     -> "Exact"
        SUBDOMAIN -> "Subdomain"
        CONTAINS  -> "Contains"
        PREFIX    -> "Prefix"
        SUFFIX    -> "Suffix"
    }

    fun description() = when (this) {
        EXACT     -> "exact domain only"
        SUBDOMAIN -> "domain + all subdomains"
        CONTAINS  -> "keyword anywhere in domain"
        PREFIX    -> "domain starts with pattern"
        SUFFIX    -> "domain ends with pattern"
    }
}

@Entity(tableName = "filter_rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String?,          // null = global
    val pattern: String,
    val matchType: MatchType = MatchType.SUBDOMAIN,
    val action: RuleAction  = RuleAction.BLOCK,
    val isEnabled: Boolean  = true
)