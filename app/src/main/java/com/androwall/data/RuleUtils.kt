package com.androwall.data

/**
 * BLACKLIST mode: allow everything except BLOCK-matched domains.
 *                 ALLOW rules act as whitelist exceptions inside that.
 * WHITELIST mode: block everything except ALLOW-matched domains.
 *                 BLOCK rules can further restrict allowed domains.
 */
fun isEffectivelyBlocked(
    domain: String,
    rules: List<FilterRule>,
    mode: FilterMode = FilterMode.BLACKLIST
): Boolean {
    val lower = domain.lowercase()
    val active = rules.filter { it.isEnabled }
    val allowRules = active.filter { it.action == RuleAction.ALLOW }
    val blockRules  = active.filter { it.action == RuleAction.BLOCK }

    val hasAllowMatch = allowRules.any { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }
    val hasBlockMatch  = blockRules.any  { ruleMatches(lower, it.pattern.lowercase(), it.matchType) }

    return when (mode) {
        FilterMode.BLACKLIST -> {
            if (hasAllowMatch) false          // explicit ALLOW beats any BLOCK rule
            else hasBlockMatch
        }
        FilterMode.WHITELIST -> {
            if (hasBlockMatch) true           // explicit BLOCK beats any ALLOW rule
            else !hasAllowMatch               // block everything not in the allow list
        }
    }
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
    MatchType.EXACT     -> "Exact domain only"
    MatchType.SUBDOMAIN -> "Domain + all subdomains"
    MatchType.PREFIX    -> "Domains starting with…"
    MatchType.SUFFIX    -> "Domains ending with…"
    MatchType.CONTAINS  -> "Domains containing…"
}