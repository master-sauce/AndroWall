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

enum class ConnectionType { DNS, HTTP, HTTPS }

@Entity(tableName = "connection_logs")
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val domain: String,
    val url: String? = null,                             // HTTP: full URL · HTTPS: https://host · DNS: null
    val connectionType: ConnectionType = ConnectionType.DNS,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean
)

enum class MatchType {
    EXACT, SUBDOMAIN, CONTAINS, PREFIX, SUFFIX, WILDCARD
}

enum class RuleAction { BLOCK, ALLOW }

enum class FilterMode { BLACKLIST, WHITELIST }

/** DNS = match against resolved domain only · URL = match against full URL / SNI · ANY = both */
enum class RuleScope { DNS, URL, ANY }

@Entity(tableName = "filter_rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String?,
    val pattern: String,
    val matchType: MatchType   = MatchType.SUBDOMAIN,
    val action: RuleAction     = RuleAction.BLOCK,
    val isEnabled: Boolean     = true,
    val scope: RuleScope       = RuleScope.ANY
)