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
//  Every list auto-updates on the 7-day cadence while enabled. All ship a
//  bundled copy in assets/filters/ so first launch blocks offline.
//
//  DNS-level reality: this engine only consumes domain rules (||domain^ /
//  @@||domain^ / hosts lines) — path rules are irrelevant for a resolver and
//  are skipped by the parser. Wildcard rules cost a regex scan per DNS query,
//  so both parsing and merging enforce hard caps (see repo constants).
//
//  defaultEnabled=false keeps foreign-language lists and the 233k-rule oisd
//  list OFF until the user switches them on — parsing every list at once is
//  an OutOfMemory risk inside the always-on VPN process.
//
//  enforceChecksum=false is for ABP's anti-adblock list only: its published
//  "! Checksum:" header does not follow the standard tooling digest.
//
enum class ListInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val assetFile: String,
    val url: String,
    val autoUpdate: Boolean,
    val enforceChecksum: Boolean = true,
    val defaultEnabled: Boolean = true
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
        url = "https://easylist.to/easylist/easyprivacy.txt",
        autoUpdate = true
    ),
    EASYLIST_COOKIE(
        id = "easylist_cookie",
        displayName = "EasyList Cookie",
        description = "Cookie consent notices",
        assetFile = "easylistcookie.txt",
        url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
        autoUpdate = true
    ),
    EASYLIST_HEBREW(
        id = "easylist_hebrew",
        displayName = "EasyList Hebrew",
        description = "Hebrew-language ads",
        assetFile = "easylisthebrew.txt",
        url = "https://raw.githubusercontent.com/easylist/EasyListHebrew/master/EasyListHebrew.txt",
        autoUpdate = true
    ),

    // ── uBlock Origin lists ──────────────────────────────────────────────────
    UBLOCK(
        id = "ublock",
        displayName = "uBlock filters",
        description = "uBO's curated ad rules",
        assetFile = "ublock.txt",
        url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        autoUpdate = true
    ),
    UBLOCK_UNBREAK(
        id = "ublock_unbreak",
        displayName = "uBlock — Unbreak",
        description = "Exception rules for broken sites",
        assetFile = "ublock_unbreak.txt",
        url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
        autoUpdate = true
    ),
    UBLOCK_ABUSE(
        id = "ublock_abuse",
        displayName = "uBlock — Resource abuse",
        description = "Malware & resource abuse",
        assetFile = "ublock_abuse.txt",
        url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/resource-abuse.txt",
        autoUpdate = true
    ),
    UBLOCK_ANNOY(
        id = "ublock_annoy",
        displayName = "uBlock — Other annoyances",
        description = "Popups, overlays, social widgets",
        assetFile = "ublock_annoy.txt",
        url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt",
        autoUpdate = true
    ),

    // ── AdGuard lists ────────────────────────────────────────────────────────
    ADGUARD_BASE(
        id = "adguard_base",
        displayName = "AdGuard Base",
        description = "AdGuard ads (no EasyList overlap)",
        assetFile = "adguard_base.txt",
        url = "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
        autoUpdate = true
    ),
    ADGUARD_MOBILE(
        id = "adguard_mobile",
        displayName = "AdGuard Mobile Ads",
        description = "Mobile app & in-app ads",
        assetFile = "adguard_mobile.txt",
        url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        autoUpdate = true
    ),
    ADGUARD_ANNOY(
        id = "adguard_annoy",
        displayName = "AdGuard Annoyances",
        description = "Banners, popups, cookie nagging",
        assetFile = "adguard_annoy.txt",
        url = "https://filters.adtidy.org/extension/ublock/filters/14.txt",
        autoUpdate = true
    ),
    ADGUARD_SOCIAL(
        id = "adguard_social",
        displayName = "AdGuard Social",
        description = "Social media widgets & trackers",
        assetFile = "adguard_social.txt",
        url = "https://filters.adtidy.org/extension/ublock/filters/9.txt",
        autoUpdate = true
    ),
    ADGUARD_RU(
        id = "adguard_ru",
        displayName = "AdGuard Russian",
        description = "Russian-language ads",
        assetFile = "adguard_ru.txt",
        url = "https://filters.adtidy.org/windows/filters/1.txt?id=1",
        autoUpdate = true,
        defaultEnabled = false
    ),
    ADGUARD_TR(
        id = "adguard_tr",
        displayName = "AdGuard Turkish",
        description = "Turkish-language ads",
        assetFile = "adguard_tr.txt",
        url = "https://filters.adtidy.org/windows/filters/13.txt?id=13",
        autoUpdate = true,
        defaultEnabled = false
    ),

    // ── Fanboy lists ─────────────────────────────────────────────────────────
    FANBOY_ANNOY(
        id = "fanboy_annoy",
        displayName = "Fanboy Annoyances",
        description = "Overlaps AdGuard Annoyances",
        assetFile = "fanboy_annoy.txt",
        url = "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
        autoUpdate = true,
        defaultEnabled = false
    ),
    FANBOY_INDIAN(
        id = "fanboy_indian",
        displayName = "Fanboy Indian",
        description = "Indian-language ads",
        assetFile = "fanboy_indian.txt",
        url = "https://secure.fanboy.co.nz/fanboy-indian.txt",
        autoUpdate = true,
        defaultEnabled = false
    ),

    // ── anti-adblock ─────────────────────────────────────────────────────────
    ANTIADB_ABP(
        id = "antiadb_abp",
        displayName = "Anti-Adblock (ABP)",
        description = "Blocks anti-adblock scripts",
        assetFile = "antiadb_abp.txt",
        url = "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
        autoUpdate = true,
        enforceChecksum = false
    ),
    ANTIADB_REEK(
        id = "antiadb_reek",
        displayName = "Anti-Adblock Killer",
        description = "Reek's anti-adblock rules",
        assetFile = "antiadb_reek.txt",
        url = "https://raw.githubusercontent.com/reek/anti-adblock-killer/master/anti-adblock-killer-filters.txt",
        autoUpdate = true
    ),
    FUCKFUCK(
        id = "fuckfuck",
        displayName = "Fuck Fuckadblock",
        description = "Anti-adblock bypass filters",
        assetFile = "fuckfuck.txt",
        url = "https://raw.githubusercontent.com/bogachenko/fuckfuckadblock/master/fuckfuckadblock.txt",
        autoUpdate = true
    ),

    // ── big community blocklists ─────────────────────────────────────────────
    YOYO_HOSTS(
        id = "yoyo_hosts",
        displayName = "Peter Lowe (hosts)",
        description = "Ads + trackers, hosts format",
        assetFile = "yoyo_hosts.txt",
        url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=1&mimetype=plaintext",
        autoUpdate = true
    ),
    OISD_BIG(
        id = "oisd_big",
        displayName = "oisd big",
        description = "233k domains — heavy on memory",
        assetFile = "oisd_big.txt",
        url = "https://abp.oisd.nl",
        autoUpdate = true,
        defaultEnabled = false
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
    LOADING, READY, UPDATING, UPDATE_FAILED, EMPTY, UPDATED
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

    // ── DNS hot-path failsafes ───────────────────────────────────────────────
    // isBlocked() runs on EVERY DNS query and wildcard rules cost a regex scan
    // each, so wildcards are hard-capped per list and in the merged engine — a
    // hostile/huge list can never slow the resolver down.
    private const val MAX_WILDCARDS_PER_LIST = 500
    private const val MAX_WILDCARDS_TOTAL = 2_000
    // downloads with more lines than this are rejected outright (VPN heap guard)
    private const val MAX_LIST_LINES = 400_000

    /** parsed engine per loaded list — toggles and refreshes never re-parse 22 MB */
    private val parsedCache = java.util.concurrent.ConcurrentHashMap<ListInfo, BlockerEngine>()

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
            // a killed process can leave half-written "<id>.txt.tmp" updates behind —
            // never read (the active cache is "<id>.txt"), only wasted space
            runCatching { dir.listFiles { f -> f.name.endsWith(".txt.tmp") }?.forEach { it.delete() } }
            loadAll()
            maybeRefresh()
        }
    }

    // ── Per-list enable/disable toggle ─────────────────────────────────────────

    fun setEnabled(li: ListInfo, enabled: Boolean) {
        if (!this::prefs.isInitialized) return   // called before init() — nothing to persist to
        prefs.edit().putBoolean("fl_${li.id}_enabled", enabled).apply()
        updateListState(li) { it.copy(enabled = enabled) }
        repoScope.launch {
            if (enabled) {
                // parse lazy — the list was never loaded while disabled
                if (parsedCache[li] == null) {
                    val engine = loadList(li)
                    val count = engine.blockDomains.size + engine.allowDomains.size
                    updateListState(li) {
                        it.copy(
                            ruleCount = count,
                            status = if (count == 0) ListStatus.EMPTY else ListStatus.READY
                        )
                    }
                }
            } else {
                parsedCache.remove(li)   // free the domains — the VPN process is memory-tight
            }
            recompile()
        }
    }

    private fun isEnabled(li: ListInfo): Boolean =
        prefs.getBoolean("fl_${li.id}_enabled", li.defaultEnabled)

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

    /**
     * Parses one list from the cache file (or the bundled asset on first run).
     * Results live in [parsedCache] — the parsed engine is reused until the
     * list is refreshed or disabled, so a toggle never costs a re-parse.
     * Throwable-guarded: one oversized/corrupt list can't crash the VPN.
     */
    private fun loadList(li: ListInfo): BlockerEngine {
        parsedCache[li]?.let { return it }
        val result = try {
            val cached = File(dir, "${li.id}.txt")
            val text = when {
                cached.exists() -> cached.readText()
                li.assetFile.isNotEmpty() -> {
                    val asset = appContext.assets.open("filters/${li.assetFile}")
                        .bufferedReader().use { it.readText() }
                    runCatching {
                        dir.mkdirs()
                        cached.writeText(asset)
                    }
                    asset
                }
                else -> return BlockerEngine.EMPTY
            }
            parseEasyList(text)
        } catch (t: Throwable) {
            // OOM / corrupt cache / missing asset — this ONE list stays empty
            Log.w(TAG, "List ${li.id} failed to load: ${t.message}")
            BlockerEngine.EMPTY
        }
        parsedCache[li] = result
        return result
    }

    // ── Auto-refresh (every enabled list, 7-day cadence) ───────────────────────

    /**
     * Sequential on purpose: one download at a time with a short gap, then ONE
     * engine rebuild at the end. 17 parallel downloads + a full reprocess per
     * list is what starves the VPN process (and its foreground DNS path).
     */
    private fun maybeRefresh() {
        val todo = ListInfo.entries.filter { li -> li.autoUpdate && isEnabled(li) && stale(li) }
        if (todo.isEmpty()) return
        for (li in todo) {
            runCatching { doRefresh(li, recompileAfter = false) }
            runCatching { Thread.sleep(500) }
        }
        runCatching { recompile() }
    }

    /** Manual refresh of a single list. */
    fun refresh(li: ListInfo) {
        if (li.url.isEmpty() || !this::prefs.isInitialized) return
        repoScope.launch { doRefresh(li) }
    }

    private fun stale(li: ListInfo): Boolean {
        val file = File(dir, "${li.id}.txt")
        if (!file.exists()) return true
        val ts = prefs.getLong("fl_${li.id}_ts", 0L)
        if (ts <= 0L) return true
        return System.currentTimeMillis() - ts > staleAfter.toMillis()
    }

    private fun doRefresh(li: ListInfo, recompileAfter: Boolean = true) {
        Log.d(TAG, "Refreshing ${li.id} from ${li.url}...")
        updateListState(li) { it.copy(status = ListStatus.UPDATING) }

        val text = try {
            // two attempts — app launch often races the network coming up
            var out: String? = null
            var last: Exception? = null
            for (attempt in 1..2) {
                try {
                    out = download(li.url)
                    break
                } catch (e: Exception) {
                    last = e
                    if (attempt == 1) runCatching { Thread.sleep(800) }
                }
            }
            out ?: throw (last ?: IOException("download failed"))
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${li.id}: ${e.message}")
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return  // cached copy stays active
        }

        val error = validate(text, li)
        if (error != null) {
            Log.w(TAG, "Validation failed for ${li.id}: $error — keeping cached copy")
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return
        }

        // parse BEFORE writing — a list that can't be parsed never replaces the cache
        val parsed = try {
            parseEasyList(text)
        } catch (t: Throwable) {
            Log.w(TAG, "Parse failed for ${li.id}: ${t.message} — keeping cached copy")
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return
        }

        // Atomic write: .tmp then rename (a killed process can't corrupt the active list)
        dir.mkdirs()
        val tmp = File(dir, "${li.id}.txt.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, "${li.id}.txt"))) {
            tmp.delete()
            Log.w(TAG, "Rename failed for ${li.id} — cache not updated")
            updateListState(li) { it.copy(status = ListStatus.UPDATE_FAILED) }
            return
        }

        prefs.edit().putLong("fl_${li.id}_ts", System.currentTimeMillis()).apply()
        parsedCache[li] = parsed
        val count = parsed.blockDomains.size + parsed.allowDomains.size
        updateListState(li) {
            it.copy(
                ruleCount = count,
                lastUpdated = lastUpdated(li),
                status = if (count == 0) ListStatus.EMPTY else ListStatus.READY
            )
        }
        if (recompileAfter && loaded) recompile()
        // visible success marker for this session — a later launch starts out READY again
        updateListState(li) {
            it.copy(status = ListStatus.UPDATED, ruleCount = count, lastUpdated = lastUpdated(li))
        }
        Log.d(TAG, "Refreshed ${li.id} — $count DNS rules")
    }

    /** Engine rebuild from the parsed cache — no disk, no re-parse. */
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
                    status = when {
                        count > 0 ->
                            if (it.status == ListStatus.UPDATED) ListStatus.UPDATED else ListStatus.READY
                        it.status == ListStatus.UPDATING -> ListStatus.UPDATING
                        else -> ListStatus.EMPTY
                    }
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
    //  Rejects truncated/corrupted/oversized downloads so the cache survives:
    //    1. Minimum rule count (non-comment, non-header lines)
    //    2. Maximum line count — a hostile list must not exhaust the VPN heap
    //    3. If "! Checksum:" header present → verify Base64(MD5) digest
    //       (skipped for lists whose published header follows other tooling)
    //

    private fun validate(text: String, li: ListInfo): String? {
        var rules = 0
        var lines = 0
        for (l in text.lineSequence()) {
            lines++
            val t = l.trim()
            if (t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("[")) rules++
        }
        if (rules < 40) return "too few rules ($rules)"
        if (lines > MAX_LIST_LINES) return "too large ($lines lines)"
        if (!li.enforceChecksum) return null

        // Checksum verification (if header present)
        val header = text.lineSequence()
            .firstOrNull { it.trim().startsWith("! Checksum:") }
            ?.substringAfter("! Checksum:")?.trim()
            ?: return null  // no checksum header → size check only

        val computed = checksum(text)
        // padding-insensitive on both sides — some publishers keep the '=' padding
        return if (!header.trimEnd('=').equals(computed, ignoreCase = true)) "checksum mismatch" else null
    }

    /** Adblock Plus checksum: remove the checksum line, MD5 the rest, Base64 encode
     *  WITHOUT padding — published headers are padding-stripped (e.g. "fKeJBo…9rw"). */
    private fun checksum(text: String): String {
        val stripped = text.lines()
            .filterNot { it.trim().startsWith("! Checksum:") }
            .joinToString("\n")
        val md5 = MessageDigest.getInstance("MD5")
            .digest(stripped.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md5, Base64.NO_WRAP).trimEnd('=')
    }

    // ── Adblock Plus / hosts parser (DNS-relevant domain rules only) ──────────
    //
    //  Extracts ||domain^ rules → SUBDOMAIN match (blockDomains set)
    //  Extracts @@||domain^ rules → allow exception (allowDomains set)
    //  Extracts hosts lines ("0.0.0.0 domain" / "127.0.0.1 domain") → blockDomains
    //  Wildcard * patterns → regex, HARD-CAPPED (a regex is scanned per DNS query)
    //
    //  Skipped (not applicable at DNS level):
    //    - Comments (!, [) and empty lines
    //    - URL-path filters (no || prefix)
    //    - Rules with / : ^ in the domain part (URL-specific, not pure domains)
    //    - $domain= / $sitekey= modifiers (context-dependent, can't evaluate at DNS)
    //

    /** hosts-file line — `0.0.0.0 domain.tld` / `127.0.0.1 domain.tld` (+ trailing comment). */
    private val hostsLine = Regex("""^(?:0\.0\.0\.0|127\.0\.0\.1|::1?)\s+([^#\s]+)""")

    private fun parseEasyList(text: String): BlockerEngine {
        val blockDomains = mutableSetOf<String>()
        val allowDomains = mutableSetOf<String>()
        val blockWildcards = mutableListOf<Regex>()
        val allowWildcards = mutableListOf<Regex>()

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("!") || trimmed.startsWith("[")) continue

            // hosts-format lines → the same ||domain^ domain rule (prefix check first:
            // a regex probe on every line of a 60k-line list is startup latency)
            val normalized =
                if (trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1") || trimmed.startsWith("::1"))
                    hostsLine.find(trimmed)?.let { "||${it.groupValues[1]}^" } ?: trimmed
                else trimmed

            // Allowlist exception (@@)
            val isAllow = normalized.startsWith("@@")
            val rule = if (isAllow) normalized.substring(2) else normalized

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
                // DNS hot-path cap — wildcards are regex scans on every query
                if (blockWildcards.size + allowWildcards.size >= MAX_WILDCARDS_PER_LIST) continue
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
            // merged wildcard cap — bounds the per-DNS-query regex work
            blockWildcards = engines.flatMap { it.blockWildcards }.take(MAX_WILDCARDS_TOTAL),
            allowWildcards = engines.flatMap { it.allowWildcards }.take(MAX_WILDCARDS_TOTAL)
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
        // loadAll / toggle / refresh run on parallel IO coroutines — serialize the
        // read-modify-write so no status update ever gets lost
        synchronized(_listStates) {
            _listStates.value = _listStates.value.map { if (it.info == li) transform(it) else it }
        }
    }

    /** Formats timestamp for display; returns "—" if 0. */
    fun formatDate(ts: Long): String =
        if (ts <= 0L) "—" else dateFmt.format(Date(ts))
}
