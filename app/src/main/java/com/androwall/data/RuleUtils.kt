package com.androwall.data

/**
 * BLACKLIST: allow all except BLOCK-matched.  ALLOW rules are whitelist exceptions.
 * WHITELIST: block all except ALLOW-matched.  BLOCK rules can further restrict.
 *
 * Scope:
 *   DNS → match target is domain name only
 *   URL → match target is full URL (http://host/path) or https://host
 *   ANY → match against both domain and URL
 */

// ── DNS matching ──────────────────────────────────────────────────────────────

fun isEffectivelyBlocked(
    domain: String,
    rules: List<FilterRule>,
    mode: FilterMode = FilterMode.BLACKLIST
): Boolean {
    val lower  = domain.lowercase()
    val active = rules.filter { it.isEnabled && (it.scope == RuleScope.DNS || it.scope == RuleScope.ANY) }

    val hasAllow = active.filter { it.action == RuleAction.ALLOW }
        .any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }
    val hasBlock = active.filter { it.action == RuleAction.BLOCK }
        .any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }

    return when (mode) {
        FilterMode.BLACKLIST -> if (hasAllow) false else hasBlock
        FilterMode.WHITELIST -> if (hasBlock) true  else !hasAllow
    }
}

// ── URL / SNI matching ────────────────────────────────────────────────────────

fun isEffectivelyBlockedForUrl(
    host: String,
    url: String?,
    rules: List<FilterRule>,
    mode: FilterMode = FilterMode.BLACKLIST
): Boolean {
    val hostLower = host.lowercase()
    val urlLower  = url?.lowercase()
    val active    = rules.filter { it.isEnabled }

    fun matches(rule: FilterRule): Boolean {
        val p = rule.pattern.lowercase()
        return when (rule.scope) {
            RuleScope.DNS -> ruleMatches(hostLower, p, rule.matchType)
            RuleScope.URL -> urlLower != null && ruleMatches(urlLower, p, rule.matchType)
            RuleScope.ANY ->
                ruleMatches(hostLower, p, rule.matchType) ||
                        (urlLower != null && ruleMatches(urlLower, p, rule.matchType))
        }
    }

    val hasAllow = active.filter { it.action == RuleAction.ALLOW }.any { matches(it) }
    val hasBlock = active.filter { it.action == RuleAction.BLOCK }.any { matches(it) }

    return when (mode) {
        FilterMode.BLACKLIST -> if (hasAllow) false else hasBlock
        FilterMode.WHITELIST -> if (hasBlock) true  else !hasAllow
    }
}

// ── Rule matching ─────────────────────────────────────────────────────────────

fun ruleMatches(target: String, pattern: String, type: MatchType): Boolean = when (type) {
    MatchType.EXACT     -> target == pattern
    MatchType.SUBDOMAIN -> target == pattern || target.endsWith(".$pattern")
    MatchType.PREFIX    -> target.startsWith(pattern)
    MatchType.SUFFIX    -> target.endsWith(pattern)
    MatchType.CONTAINS  -> target.contains(pattern)
    MatchType.WILDCARD  -> wildcardMatch(pattern, target)
}

fun wildcardMatch(pattern: String, input: String): Boolean {
    val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
    return Regex("^$regex$").matches(input)
}

// ── MatchType display helpers ─────────────────────────────────────────────────

fun MatchType.displayName(): String = when (this) {
    MatchType.EXACT     -> "Exact"
    MatchType.SUBDOMAIN -> "Subdomain"
    MatchType.PREFIX    -> "Prefix"
    MatchType.SUFFIX    -> "Suffix"
    MatchType.CONTAINS  -> "Contains"
    MatchType.WILDCARD  -> "Wildcard *"
}

fun MatchType.description(): String = when (this) {
    MatchType.EXACT     -> "Exact domain only"
    MatchType.SUBDOMAIN -> "Domain + all subdomains"
    MatchType.PREFIX    -> "Domains / URLs starting with…"
    MatchType.SUFFIX    -> "Domains / URLs ending with…"
    MatchType.CONTAINS  -> "Domains / URLs containing…"
    MatchType.WILDCARD  -> "Use * for any sequence of chars"
}