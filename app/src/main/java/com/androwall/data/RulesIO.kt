package com.androwall.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Import/export helpers for [FilterRule] lists.
 *
 * Uses Android's built-in [org.json] so no extra serialization dependency is required.
 *
 * JSON envelope shape (version 1):
 * ```
 * {
 *   "type": "androwall-rules",
 *   "version": 1,
 *   "scope": "global" | "app:<packageName>",
 *   "exportedAt": <epoch millis>,
 *   "filterMode": "BLACKLIST" | "WHITELIST",   // optional, only for global exports
 *   "rules": [
 *     { "pattern": "...", "matchType": "SUBDOMAIN", "action": "BLOCK", "isEnabled": true }
 *   ]
 * }
 * ```
 *
 * The `id` and `packageName` fields are intentionally NOT serialized — they are
 * database-specific and reassigned on import so the same file can be re-imported
 * into either global or per-app scope without colliding with existing rows.
 */
object RulesIO {

    const val MIME_TYPE = "application/json"
    const val FILE_HEADER = "androwall-rules"
    const val FORMAT_VERSION = 1

    /** Result of parsing an export file. */
    data class RulesImport(
        val scope: String,                // "global" or "app:<pkg>"
        val filterMode: FilterMode?,      // only present for global exports
        val rules: List<FilterRule>       // packageName = null, id = 0 (caller overrides)
    )

    /**
     * Serializes the given rules to a JSON string.
     *
     * @param rules   the rules to export
     * @param scope   "global" or "app:<packageName>"
     * @param filterMode optional filter mode (only meaningful for global exports)
     */
    fun serialize(
        rules: List<FilterRule>,
        scope: String,
        filterMode: FilterMode? = null
    ): String {
        val root = JSONObject()
        root.put("type", FILE_HEADER)
        root.put("version", FORMAT_VERSION)
        root.put("scope", scope)
        root.put("exportedAt", System.currentTimeMillis())
        filterMode?.let { root.put("filterMode", it.name) }

        val arr = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("pattern", rule.pattern)
            obj.put("matchType", rule.matchType.name)
            obj.put("action", rule.action.name)
            obj.put("isEnabled", rule.isEnabled)
            arr.put(obj)
        }
        root.put("rules", arr)
        return root.toString(2)
    }

    /**
     * Parses a JSON string produced by [serialize].
     *
     * @throws IllegalArgumentException if the document is not a valid AndroWall export
     *         or references unknown enum values.
     */
    @Throws(IllegalArgumentException::class)
    fun parse(json: String): RulesImport {
        val root = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("File is not valid JSON: ${e.message}")
        }

        val type = root.optString("type", "")
        require(type == FILE_HEADER) {
            "Not an AndroWall rules file (missing/wrong \"type\" header)"
        }

        val version = root.optInt("version", 0)
        require(version <= FORMAT_VERSION) {
            "Unsupported export version $version (max supported: $FORMAT_VERSION)"
        }

        val scope = root.optString("scope", "global")

        val filterMode = root.optString("filterMode", "").takeIf { it.isNotBlank() }
            ?.let { name ->
                runCatching { FilterMode.valueOf(name) }.getOrNull()
            }

        val arr = root.optJSONArray("rules") ?: JSONArray()
        val rules = ArrayList<FilterRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val pattern = obj.optString("pattern").trim()
            if (pattern.isEmpty()) continue

            val matchType = runCatching {
                MatchType.valueOf(obj.optString("matchType", MatchType.SUBDOMAIN.name))
            }.getOrDefault(MatchType.SUBDOMAIN)

            val action = runCatching {
                RuleAction.valueOf(obj.optString("action", RuleAction.BLOCK.name))
            }.getOrDefault(RuleAction.BLOCK)

            val isEnabled = obj.optBoolean("isEnabled", true)

            rules += FilterRule(
                id = 0,
                packageName = null,
                pattern = pattern,
                matchType = matchType,
                action = action,
                isEnabled = isEnabled
            )
        }

        return RulesImport(scope = scope, filterMode = filterMode, rules = rules)
    }
}
