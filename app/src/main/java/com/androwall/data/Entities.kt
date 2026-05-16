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

enum class MatchType {
    EXACT, SUBDOMAIN, CONTAINS, PREFIX, SUFFIX, WILDCARD;

    fun displayName() = when (this) {
        EXACT     -> "Exact"
        SUBDOMAIN -> "Subdomain"
        CONTAINS  -> "Contains"
        PREFIX    -> "Prefix"
        SUFFIX    -> "Suffix"
        WILDCARD  -> "Wildcard *"
    }

    fun description() = when (this) {
        EXACT     -> "domain.com only"
        SUBDOMAIN -> "domain.com + *.domain.com"
        CONTAINS  -> "any domain with this text"
        PREFIX    -> "domains starting with"
        SUFFIX    -> "domains ending with"
        WILDCARD  -> "use * for any sequence"
    }
}

enum class RuleAction { BLOCK, ALLOW }

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