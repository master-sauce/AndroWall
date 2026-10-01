package com.androwall.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ══ Filter list identifiers ════════════════════════════════════════════════════
//
//  EASYLIST is the only list with auto-update enabled (7-day cadence).
//  The other three are bundled-only — loaded at startup, never refreshed.
//  All four can be toggled on/off by the user from the Global Rules tab.
//
enum class ListInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val assetFile: String,
    val url: String,
    val autoUpdate: Boolean
) {
    EASYLIST(
        id = "easylist",
        displayName = "EasyList",
        description = "General ad blocking",
        assetFile = "easylist.txt",
        url = "https://easylist.to/easylist/easylist.txt",
        autoUpdate = true
    ),
    EASYPRIVACY(
        id = "easyprivacy",
        displayName = "EasyPrivacy",
        description = "Tracking & analytics",
        assetFile = "easyprivacy.txt",
        url = "",
        autoUpdate = false
    ),
    EASYLIST_COOKIE(
        id = "easylist_cookie",
        displayName = "EasyList Cookie",
        description = "Cookie consent notices",
        assetFile = "easylistcookie.txt",
        url = "",
        autoUpdate = false
    ),
    EASYLIST_HEBREW(
        id = "easylist_hebrew",
        displayName = "EasyList Hebrew",
        description = "Hebrew-language ads",
        assetFile = "easylisthebrew.txt",
        url = "",
        autoUpdate = false
    );
}

// ══ UI-facing list state ════════════════════════════════════════════════════════

data class ListState(
    val info: ListInfo,
    val enabled: Boolean,
    val ruleCount: Int,
    val lastUpdated: Long,     // epoch millis, 0 = never
    val status: ListStatus
)

enum class ListStatus {
    LOADING, READY, UPDATING, UPDATE_FAILED, EMPTY
}

// ══ Compiled domain-blocking engine ════════════════════════════════════════════
//
//  Holds parsed EasyList rules as domain sets + wildcard regexes.
//  isBlocked() returns:
//    true  → domain matches a || block rule
//    false → domain matches an @@ allow exception (overrides block)
//    null  → no match (caller falls through to user rules / mode default)
//
//  Allow rules always take priority over block rules within the engine,
//  matching Adblock Plus semantics where @@ exceptions override || filters.
//
class BlockerEngine(
    val blockDomains: Set<String>,
    val allowDomains: Set<String>,
    val blockWildcards: List<Regex>,
    val allowWildcards: List<Regex>
) {
    companion object {
        val EMPTY = BlockerEngine(
            blockDomains = emptySet(),
            allowDomains = emptySet(),
            blockWildcards = emptyList(),
            allowWildcards = emptyList()
        )
    }

    fun isBlocked(domain: String): Boolean? {
        if (matchesAllow(domain)) return false
        if (matchesBlock(domain)) return true
        return null
    }

    // ── Allow (@@ exceptions) ────────────────────────────────────────────────

    private fun matchesAllow(domain: String): Boolean {
        if (domain in allowDomains) return true
        // Parent-domain walk: ||example.com^ matches sub.example.com too
        var d = domain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (d in allowDomains) return true
        }
        for (r in allowWildcards) if (r.matches(domain)) return true
        return false
    }

    // ── Block (|| rules) ─────────────────────────────────────────────────────

    private fun matchesBlock(domain: String): Boolean {
        if (domain in blockDomains) return true
        var d = domain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (d in blockDomains) return true
        }
        for (r in blockWildcards) if (r.matches(domain)) return true
        return false
    }
}

// ══ Repository: bundled-asset loading + EasyList auto-update + toggle ════════════
//
//  Flow:
//    init() → loadAll() → maybeRefresh()
//      loadAll()      reads cached/bundled file for each ENABLED list, parses, merges
//      maybeRefresh() checks EASYLIST staleness (>7 days) and downloads if stale
//
//  Toggle:
//    setEnabled(li, enabled) persists to SharedPreferences and recompiles the engine.
//    Disabled lists are excluded from the merged engine entirely.
//
//  Download safety:
//    - validate() rejects corrupted/truncated downloads (min rule count + checksum)
//    - atomic write via .tmp + rename — crash mid-write can't corrupt active list
//    - on any failure the cached/bundled copy stays active
//
//  Engine swap:
//    StateFlow<BlockerEngine> — VPN reads .value, always gets the latest consistent engine.
//
object FilterListRepo {
    private const val TAG = "FilterListRepo"
    private val staleAfter = java.time.Duration.ofDays(7)

    @Volatile
    private var initialized = false

    @Volatile
    private var loaded = false

    // ── Observable state for VPN engine + UI ──────────────────────────────────

    private val _engineFlow = MutableStateFlow(BlockerEngine.EMPTY)

    /** VPN reads .value — always the latest compiled engine from enabled lists. */
    val engineFlow: StateFlow<BlockerEngine> = _engineFlow.asStateFlow()

    private val _listStates = MutableStateFlow<List<ListState>>(emptyList())

    /** UI observes this to render toggle cards. */
    val listStates: StateFlow<List<ListState>> = _listStates.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    // ── Initialization (idempotent, call from UI or service) ──────────────────

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            appContext = context.applicationContext
            dir = File(appContext.filesDir, "filter_lists")
            prefs = appContext.getSharedPreferences("filter_list_prefs", Context.MODE_PRIVATE)
        }
        // Publish initial loading states so UI shows something immediately
        _listStates.value = ListInfo.entries.map {
            ListState(it, isEnabled(it), 0, 0L, ListStatus.LOADING)
        }
        repoScope.launch {
            loadAll()
            maybeRefresh()
        }
    }

    // ── Per-list enable/disable toggle ─────────────────────────────────────────

    fun setEnabled(li: ListInfo, enabled: Boolean) {
        prefs.edit().putBoolean("fl_${li.id}_enabled", enabled).apply()
        updateListState(li) { it.copy(enabled = enabled) }
        repoScope.launch { recompile() }
    }

    private fun isEnabled(li: ListInfo): Boolean =
        prefs.getBoolean("fl_${li.id}_enabled", true)

    // ── Load all enabled lists from cache or bundled asset ─────────────────────

    private fun loadAll() {
        val states = mutableListOf<ListState>()
        val engines = ListInfo.entries.map { li ->
            if (!isEnabled(li)) {
                states.add(ListState(li, false, 0, lastUpdated(li), ListStatus.READY))
                return@map BlockerEngine.EMPTY
            }
            val engine = loadList(li)
            val count = engine.blockDomains.size + engine.allowDomains.size
            val status = if (count == 0) ListStatus.EMPTY else ListStatus.READY
            states.add(ListState(li, true, count, lastUpdated(li), status))
            engine
        }
        engine = mergeEngines(engines)
        loaded = true
        _listStates.value = states
        Log.d(
            TAG,
            "Engine loaded — block=${engine.blockDomains.size} domains, " +
                    "allow=${engine.allowDomains.size} domains, " +
                    "blockWildcards=${engine.blockWildcards.size}, " +
                    "allowWildcards=${engine.allowWildcards.size}"
        )
    }

    private fun loadList(li: ListInfo): BlockerEngine {
        val cached = File(dir, "${li.id}.txt")
        val text = if (cached.exists()) {
            cached.readText()
        } else {
            // First run — copy bundled asset to cache.
            try {
                val asset = appContext.assets.open("filters/${li.assetFile}")
                    .bufferedReader().use { it.readText() }
                dir.mkdirs()
                cached.writeText(asset)
                asset
            } catch (e: Exception) {
                Log.w(TAG, "No cached or bundled list for ${li.id}: ${e.message}")
                return BlockerEngine.EMPTY
            }
        }
        return parseEasyList(text)
    }

    // ── Auto-refresh (EASYLIST only) ──────────────────────────────────────────

    private fun maybeRefresh() {
        for (li in ListInfo.entries) {
            if (li.autoUpdate && isEnabled(li) && stale(li)) refresh(li)
        }
    }

    /** Manual refresh of a single list. */
    fun refresh(li: ListInfo) {
        if (li.url.isEmpty()) return
        repoScope.launch { doRefresh(li) }
    }

    private fun stale(li: ListInfo): Boolean {
        val file = File(dir, "${li.id}.txt")
        if (!file.exists()) return true
        val ts = prefs.getLong("fl_${li.id}_ts", 0L)
        if (ts <= 0L) return true
        return System.currentTimeMillis() - ts > staleAfter.toMillis()
    }

    private fun doRefresh(li: ListInfo) {
        Log.d(TAG, "Refreshing ${li.id} from ${li.url}...")
        updateListState(li) { it.copy(status = ListStatus.UPDATING) }

        val text = try {
            download(li.url)
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${li.id}: ${e.message}")
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return  // cached copy stays active
        }

        val error = validate(text)
        if (error != null) {
            Log.w(TAG, "Validation failed for ${li.id}: $error — keeping cached copy")
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return
        }

        // Atomic write: .tmp then rename
        dir.mkdirs()
        val tmp = File(dir, "${li.id}.txt.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, "${li.id}.txt"))) {
            Log.w(TAG, "Rename failed for ${li.id} — cache not updated")
            tmp.delete()
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return
        }

        val ts = System.currentTimeMillis()
        prefs.edit().putLong("fl_${li.id}_ts", ts).apply()

        if (loaded) recompile()
        Log.d(TAG, "Refreshed ${li.id} — engine recompiled")
    }

    private fun recompile() {
        val engines = ListInfo.entries.map { li ->
            if (!isEnabled(li)) {
                updateListState(li) { it.copy(enabled = false) }
                return@map BlockerEngine.EMPTY
            }
            val engine = loadList(li)
            val count = engine.blockDomains.size + engine.allowDomains.size
            updateListState(li) {
                it.copy(
                    enabled = true,
                    ruleCount = count,
                    lastUpdated = lastUpdated(li),
                    status = if (count == 0) ListStatus.EMPTY else ListStatus.READY
                )
            }
            engine
        }
        engine = mergeEngines(engines)
    }

    // ── HTTP download (zero extra dependencies — uses HttpURLConnection) ───────

    private fun download(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/plain;q=1.0,*/*;q=0.8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AndroWall/1.0")
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299)
                throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Validation gate ───────────────────────────────────────────────────────
    //
    //  Rejects truncated/corrupted downloads so the cache survives:
    //    1. Minimum rule count (non-comment, non-header lines)
    //    2. If "! Checksum:" header present → verify Base64(MD5) digest
    //

    private fun validate(text: String): String? {
        val rules = text.lines().count {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("[")
        }
        if (rules < 40) return "too few rules ($rules)"

        // Checksum verification (if header present)
        val header = text.lines()
            .firstOrNull { it.trim().startsWith("! Checksum:") }
            ?.substringAfter("! Checksum:")?.trim()
            ?: return null  // no checksum header → size check only

        val computed = checksum(text)
        return if (!header.equals(computed, ignoreCase = true)) "checksum mismatch" else null
    }

    /** Adblock Plus checksum: remove the checksum line, MD5 the rest, Base64 encode. */
    private fun checksum(text: String): String {
        val stripped = text.lines()
            .filterNot { it.trim().startsWith("! Checksum:") }
            .joinToString("\n")
        val md5 = MessageDigest.getInstance("MD5")
            .digest(stripped.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md5, Base64.NO_WRAP)
    }

    // ── Adblock Plus parser (DNS-relevant domain rules only) ──────────────────
    //
    //  Extracts ||domain^ rules → SUBDOMAIN match (blockDomains set)
    //  Extracts @@||domain^ rules → allow exception (allowDomains set)
    //  Wildcard * patterns → regex (blockWildcards / allowWildcards)
    //
    //  Skipped (not applicable at DNS level):
    //    - Comments (!, [) and empty lines
    //    - URL-path filters (no || prefix)
    //    - Rules with / : ^ in the domain part (URL-specific, not pure domains)
    //    - $domain= / $sitekey= modifiers (context-dependent, can't evaluate at DNS)
    //

    private fun parseEasyList(text: String): BlockerEngine {
        val blockDomains = mutableSetOf<String>()
        val allowDomains = mutableSetOf<String>()
        val blockWildcards = mutableListOf<Regex>()
        val allowWildcards = mutableListOf<Regex>()

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("!") || trimmed.startsWith("[")) continue

            // Allowlist exception (@@)
            val isAllow = trimmed.startsWith("@@")
            val rule = if (isAllow) trimmed.substring(2) else trimmed

            // Only || rules (domain-level)
            if (!rule.startsWith("||")) continue
            var pattern = rule.substring(2)

            // Strip $ modifiers
            val dollarIdx = pattern.indexOf('$')
            if (dollarIdx >= 0) {
                val modifiers = pattern.substring(dollarIdx + 1).lowercase()
                // Skip context-dependent rules that can't be evaluated at DNS level
                if ("domain=" in modifiers || "sitekey=" in modifiers) continue
                // @@ exceptions with content-type modifiers (generichide, document,
                // image, script, etc.) are cosmetic/URL-level allows, not DNS allows.
                // Only honor bare @@ (or @@ with third-party) as DNS-level allows.
                if (isAllow) {
                    val modSet = modifiers.split(',').map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    val dnsRelevant = modSet.all { it == "third-party" || it == "~third-party" }
                    if (!dnsRelevant) continue
                }
                pattern = pattern.substring(0, dollarIdx)
            }

            // Strip trailing ^ or | separator
            pattern = pattern.trimEnd('^', '|')

            // Skip URL-path / port / mid-separator rules (not pure domains)
            if (pattern.contains('/') || pattern.contains(':') || pattern.contains('^')) continue
            if (pattern.isEmpty()) continue

            if (pattern.contains('*')) {
                val regexStr = "^" + pattern.split("*")
                    .joinToString(".*") { Regex.escape(it) } + "$"
                val regex = Regex(regexStr, RegexOption.IGNORE_CASE)
                if (isAllow) allowWildcards.add(regex) else blockWildcards.add(regex)
            } else {
                if (isAllow) allowDomains.add(pattern.lowercase())
                else blockDomains.add(pattern.lowercase())
            }
        }

        return BlockerEngine(blockDomains, allowDomains, blockWildcards, allowWildcards)
    }

    // ── Merge multiple list engines into one ──────────────────────────────────

    private fun mergeEngines(engines: List<BlockerEngine>): BlockerEngine {
        return BlockerEngine(
            blockDomains = engines.flatMap { it.blockDomains }.toSet(),
            allowDomains = engines.flatMap { it.allowDomains }.toSet(),
            blockWildcards = engines.flatMap { it.blockWildcards },
            allowWildcards = engines.flatMap { it.allowWildcards }
        )
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private var engine: BlockerEngine
        get() = _engineFlow.value
        set(value) {
            _engineFlow.value = value
        }

    private fun lastUpdated(li: ListInfo): Long =
        prefs.getLong("fl_${li.id}_ts", 0L)

    private fun updateListState(li: ListInfo, transform: (ListState) -> ListState) {
        _listStates.value = _listStates.value.map { if (it.info == li) transform(it) else it }
    }

    /** Formats timestamp for display; returns "—" if 0. */
    fun formatDate(ts: Long): String =
        if (ts <= 0L) "—" else dateFmt.format(Date(ts))
}
