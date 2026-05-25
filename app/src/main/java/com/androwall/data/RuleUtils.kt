package com.androwall.data

/**
 * BLACKLIST mode: allow all except BLOCK-matched. ALLOW rules are whitelist exceptions inside that.
 * WHITELIST mode: block all except ALLOW-matched. BLOCK rules can further restrict allowed domains.
 *
 * Priority: in BLACKLIST → ALLOW beats BLOCK. In WHITELIST → BLOCK beats ALLOW.
 */
fun isEffectivelyBlocked(
    domain: String,
    rules: List<FilterRule>,
    mode: FilterMode = FilterMode.BLACKLIST
): Boolean {
    val lower  = domain.lowercase()
    val active = rules.filter { it.isEnabled }

    val hasAllowMatch = active.filter { it.action == RuleAction.ALLOW }
        .any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }

    val hasBlockMatch = active.filter { it.action == RuleAction.BLOCK }
        .any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }

    return when (mode) {
        FilterMode.BLACKLIST -> if (hasAllowMatch) false else hasBlockMatch
        FilterMode.WHITELIST -> if (hasBlockMatch) true  else !hasAllowMatch
    }
}

fun ruleMatches(domain: String, pattern: String, type: MatchType): Boolean = when (type) {
    MatchType.EXACT     -> domain == pattern
    MatchType.SUBDOMAIN -> domain == pattern || domain.endsWith(".$pattern")
    MatchType.PREFIX    -> domain.startsWith(pattern)
    MatchType.SUFFIX    -> domain.endsWith(pattern)
    MatchType.CONTAINS  -> domain.contains(pattern)
    MatchType.WILDCARD  -> wildcardMatch(pattern, domain)
}

/** Converts a glob-style * pattern to a regex and tests the input. */
fun wildcardMatch(pattern: String, input: String): Boolean {
    val regexStr = pattern.split("*").joinToString(".*") { Regex.escape(it) }
    return Regex("^$regexStr$").matches(input)
}

// ── MatchType extensions ──────────────────────────────────────────────────────
// Note: keep in sync with Entities.kt enum — do not duplicate displayName/description there.

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
    MatchType.PREFIX    -> "Domains starting with…"
    MatchType.SUFFIX    -> "Domains ending with…"
    MatchType.CONTAINS  -> "Domains containing…"
    MatchType.WILDCARD  -> "Use * for any sequence of chars"
}