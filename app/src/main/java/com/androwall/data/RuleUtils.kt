package com.androwall.data

/** ALLOW rules always win over BLOCK rules (whitelist-first). */
fun isEffectivelyBlocked(domain: String, rules: List<FilterRule>): Boolean {
    val lower = domain.lowercase()
    val active = rules.filter { it.isEnabled }
    if (active.filter { it.action == RuleAction.ALLOW }
            .any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }) return false
    return active.filter { it.action == RuleAction.BLOCK }
        .any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }
}

fun ruleMatches(domain: String, pattern: String, type: MatchType): Boolean = when (type) {
    MatchType.EXACT     -> domain == pattern
    MatchType.SUBDOMAIN -> domain == pattern || domain.endsWith(".$pattern")
    MatchType.PREFIX    -> domain.startsWith(pattern)
    MatchType.SUFFIX    -> domain.endsWith(pattern)
    MatchType.CONTAINS  -> domain.contains(pattern)
}

fun MatchType.displayName() = when (this) {
    MatchType.EXACT     -> "Exact"
    MatchType.SUBDOMAIN -> "Subdomain"
    MatchType.PREFIX    -> "Prefix"
    MatchType.SUFFIX    -> "Suffix"
    MatchType.CONTAINS  -> "Contains"
}

fun MatchType.description() = when (this) {
    MatchType.EXACT     -> "Exact domain match only"
    MatchType.SUBDOMAIN -> "Domain and all its subdomains"
    MatchType.PREFIX    -> "Domains starting with pattern"
    MatchType.SUFFIX    -> "Domains ending with pattern"
    MatchType.CONTAINS  -> "Domains containing pattern"
}