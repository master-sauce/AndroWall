package com.androwall

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.androwall.data.*
import com.androwall.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// ── Entry point ───────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreference.load(this)
        val initialBg = if (ThemePreference.isDark.value)
            android.graphics.Color.parseColor("#0D0B09")
        else
            android.graphics.Color.parseColor("#FAF6F2")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(initialBg))
        enableEdgeToEdge()
        setContent {
            AndroWallTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    AndroWallApp()
                }
            }
        }
    }
}

// ── Nav host ──────────────────────────────────────────────────────────────────

@Composable
fun AndroWallApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val dao = AppDatabase.getDatabase(context).appDao()
    val c = LocalAppColors.current

    LaunchedEffect(Unit) { VpnTrackerService.loadPersistedMode(context) }

    SideEffect {
        (context as? android.app.Activity)?.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(
                    255,
                    (c.background.red   * 255).toInt(),
                    (c.background.green * 255).toInt(),
                    (c.background.blue  * 255).toInt()
                )
            )
        )
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        NavHost(
            navController, startDestination = "main",
            enterTransition    = { EnterTransition.None },
            exitTransition     = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition  = { ExitTransition.None }
        ) {
            composable("main") { MainScreen(navController, dao) }
            composable(
                "app_detail/{packageName}",
                arguments = listOf(navArgument("packageName") { type = NavType.StringType })
            ) { back ->
                AppDetailScreen(navController, dao, back.arguments?.getString("packageName") ?: "")
            }
        }
    }
}

// ── Domain match helpers ──────────────────────────────────────────────────────

fun wildcardMatch(pattern: String, input: String): Boolean {
    val regexStr = pattern.split("*").joinToString(".*") { Regex.escape(it) }
    return Regex("^$regexStr$").matches(input)
}

fun domainMatchesRule(domain: String, rule: FilterRule): Boolean {
    val lower = domain.lowercase()
    val p     = rule.pattern.lowercase()
    return when (rule.matchType) {
        MatchType.EXACT     -> lower == p
        MatchType.SUBDOMAIN -> lower == p || lower.endsWith(".$p")
        MatchType.CONTAINS  -> lower.contains(p)
        MatchType.PREFIX    -> lower.startsWith(p)
        MatchType.SUFFIX    -> lower.endsWith(p)
        MatchType.WILDCARD  -> wildcardMatch(p, lower)
    }
}

fun findMatchingRule(domain: String, rules: List<FilterRule>): FilterRule? =
    rules.filter { it.isEnabled }.firstOrNull { domainMatchesRule(domain, it) }

fun isEffectivelyBlocked(domain: String, rules: List<FilterRule>, mode: FilterMode): Boolean {
    val match = findMatchingRule(domain, rules)
    return when {
        match != null                -> match.action == RuleAction.BLOCK
        mode == FilterMode.WHITELIST -> true
        else                         -> false
    }
}


/**
 * Generates example domains that a wildcard pattern would match.
 * Works with any number of * wildcards — e.g. *.ads.*.com, *tracker*cdn*
 */
fun generateWildcardExamples(pattern: String): List<String> {
    if (!pattern.contains('*')) return emptyList()

    val parts    = pattern.split("*")           // segments between stars
    val starCount = parts.size - 1

    // Different filler words per example so output looks realistic
    val fillerSets = listOf(
        listOf("www", "cdn", "api", "static", "assets"),
        listOf("ads", "track", "pixel", "beacon", "metric"),
        listOf("s3", "media", "img", "data", "files")
    )

    return (0 until 3).mapNotNull { exampleIdx ->
        val fillers = fillerSets[exampleIdx]
        val sb = StringBuilder()
        parts.forEachIndexed { partIdx, segment ->
            sb.append(segment)
            if (partIdx < starCount) {
                // pick a different filler word per star position
                sb.append(fillers[(exampleIdx + partIdx) % fillers.size])
            }
        }
        val result = sb.toString()
        // Filter out blanks and patterns that look like the original (no substitution happened)
        result.takeIf { it.isNotBlank() && it.contains('.') }
    }.distinct()
}

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, dao: AppDao) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val c       = LocalAppColors.current
    val isDark  by ThemePreference.isDark.collectAsState()

    val appConfigs  by dao.getAllAppConfigs().collectAsState(initial = emptyList())
    val recentLogs  by dao.getRecentLogs().collectAsState(initial = emptyList())
    val globalRules by dao.getGlobalRules().collectAsState(initial = emptyList())
    val filterMode  by VpnTrackerService.filterMode.collectAsState()

    var installedApps           by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var searchQuery             by remember { mutableStateOf("") }
    var logSearchQuery          by remember { mutableStateOf("") }
    var selectedTab             by remember { mutableIntStateOf(0) }
    var showErrorToast          by remember { mutableStateOf(false) }
    var toastMessage            by remember { mutableStateOf("") }
    var showClearLogsDialog     by remember { mutableStateOf(false) }
    var showAddGlobalRuleDialog by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val vpnLauncher   = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK)
            context.startService(Intent(context, VpnTrackerService::class.java))
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val pm = context.packageManager
        installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.packageName != context.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }

    val enabledPackages = remember(appConfigs) {
        appConfigs.filter { it.isFilteringEnabled }.map { it.packageName }.toSet()
    }

    val filteredApps = remember(installedApps, searchQuery, enabledPackages) {
        val pm = context.packageManager
        val base = if (searchQuery.isBlank()) installedApps
        else installedApps.filter { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
        }
        base.sortedWith(
            compareByDescending<ApplicationInfo> { it.packageName in enabledPackages }
                .thenBy { pm.getApplicationLabel(it).toString() }
        )
    }

    val filteredLogs = remember(recentLogs, logSearchQuery) {
        if (logSearchQuery.isBlank()) recentLogs
        else recentLogs.filter { it.domain.contains(logSearchQuery, ignoreCase = true) }
    }

    if (showClearLogsDialog) {
        PhoenixAlertDialog(
            title = "Clear History", text = "All connection logs will be permanently deleted.",
            confirmText = "Clear", confirmColor = c.red,
            onConfirm = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss = { showClearLogsDialog = false }
        )
    }
    if (showAddGlobalRuleDialog) {
        AddRuleDialog(
            title = "Add Global Rule",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss = { showAddGlobalRuleDialog = false },
            onAdd = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = null)) } }
        )
    }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Box(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
                Column(
                    Modifier.align(Alignment.Center).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                }
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
                        .size(36.dp)
                        .background(PhoenixFlameGhost, PhoenixShapeSmall)
                        .border(0.5.dp, PhoenixFlame.copy(0.35f), PhoenixShapeSmall)
                        .clickable { ThemePreference.toggle(context) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isDark) "☀" else "🌙", fontSize = 16.sp)
                }
                Box(
                    Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomStart)
                        .background(Brush.horizontalGradient(
                            listOf(PhoenixFlame.copy(0.7f), PhoenixFlame.copy(0.2f), Color.Transparent)))
                )
            }
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(1.dp)
                    .background(Brush.horizontalGradient(
                        listOf(Color.Transparent, PhoenixFlame.copy(0.35f), Color.Transparent))))
                NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    listOf(Triple(0, Icons.Default.Home, "Apps"),
                        Triple(1, Icons.Default.List, "Logs"),
                        Triple(2, Icons.Default.Lock, "Rules"))
                        .forEach { (idx, icon, label) ->
                            NavigationBarItem(
                                selected = selectedTab == idx,
                                onClick  = { selectedTab = idx },
                                icon     = { Icon(icon, null, Modifier.size(20.dp)) },
                                label    = { Text(label, fontWeight = FontWeight.Medium, fontSize = 11.sp) },
                                colors   = NavigationBarItemDefaults.colors(
                                    selectedIconColor   = PhoenixFlame,
                                    selectedTextColor   = PhoenixFlame,
                                    unselectedIconColor = c.textSecondary,
                                    unselectedTextColor = c.textSecondary,
                                    indicatorColor      = PhoenixFlameGhost
                                )
                            )
                        }
                }
            }
        },
        floatingActionButton = {
            when (selectedTab) {
                1 -> AnimatedVisibility(recentLogs.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    PhoenixFab(onClick = { showClearLogsDialog = true }, icon = Icons.Default.Delete,
                        color = c.red, contentDescription = "Clear logs")
                }
                2 -> PhoenixFab(onClick = { showAddGlobalRuleDialog = true }, icon = Icons.Default.Add,
                    color = PhoenixFlame, contentDescription = "Add rule")
                else -> {}
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(c.background)) {
            Column(Modifier.fillMaxSize()) {
                FirewallStatusCard(
                    hasEnabledApps = enabledPackages.isNotEmpty(),
                    onStart = {
                        if (enabledPackages.isEmpty()) {
                            toastMessage = "No apps have filtering enabled.\nGo to the Apps tab and enable at least one."
                            showErrorToast = true
                            return@FirewallStatusCard
                        }
                        val intent = VpnService.prepare(context)
                        if (intent != null) vpnLauncher.launch(intent)
                        else context.startService(Intent(context, VpnTrackerService::class.java))
                    },
                    onStop = {
                        context.startService(
                            Intent(context, VpnTrackerService::class.java)
                                .apply { action = VpnTrackerService.ACTION_STOP_VPN }
                        )
                    }
                )

                when (selectedTab) {
                    0 -> {
                        PhoenixSearchField(searchQuery, { searchQuery = it }, "Search apps...")
                        LazyColumn(Modifier.weight(1f)) {
                            if (filteredApps.isEmpty()) {
                                item { PhoenixEmptyState(Icons.Default.Search, "No matching apps found.") }
                            } else {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    val config = appConfigs.find { it.packageName == app.packageName }
                                    AppListItem(app, config) {
                                        navController.navigate("app_detail/${app.packageName}")
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        PhoenixSearchField(logSearchQuery, { logSearchQuery = it }, "Search domains...")
                        if (logSearchQuery.isNotBlank()) {
                            Text("${filteredLogs.size} of ${recentLogs.size} results",
                                fontSize = 11.sp, color = PhoenixFlameDim,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                        }
                        GlobalLogList(filteredLogs, globalRules, filterMode, dao, scope)
                    }
                    2 -> GlobalRulesTab(globalRules, filterMode, context, dao, scope)
                }
            }

            FireToastCard(
                message = toastMessage, visible = showErrorToast,
                onDismiss = { showErrorToast = false },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            )
        }
    }
}

// ── Search field ──────────────────────────────────────────────────────────────

@Composable
fun PhoenixSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val c = LocalAppColors.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(c.card, PhoenixShapeMedium)
            .border(1.dp, c.borderBright.copy(0.4f), PhoenixShapeMedium)
    ) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = c.textSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = PhoenixFlame, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (value.isNotEmpty())
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Clear, "Clear", tint = PhoenixFlameDim, modifier = Modifier.size(16.dp))
                    }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                cursorColor = PhoenixFlame
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
}

// ── Firewall status card ──────────────────────────────────────────────────────

@Composable
fun FirewallStatusCard(hasEnabledApps: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val c         = LocalAppColors.current
    val isRunning by VpnTrackerService.isRunning.collectAsState()
    val border    = if (isRunning) PhoenixFlame else c.borderMid

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .background(c.card, PhoenixShapeLarge)
            .border(1.dp, border.copy(0.4f), PhoenixShapeLarge)
            .clip(PhoenixShapeLarge)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingDot(if (isRunning) PhoenixFlame else c.textSecondary, 14.dp)
                Spacer(Modifier.height(6.dp))
                Icon(if (isRunning) Icons.Default.Lock else Icons.Default.LockOpen, null,
                    tint = if (isRunning) PhoenixFlame else c.textSecondary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isRunning) "Firewall Active" else "Firewall Offline",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (isRunning) PhoenixFlame else c.textSecondary)
                Spacer(Modifier.height(2.dp))
                Text(if (isRunning) "Monitoring DNS traffic" else "Engine is stopped",
                    fontSize = 12.sp, color = if (isRunning) PhoenixFlame.copy(0.65f) else c.textTertiary)
                if (!isRunning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasEnabledApps) "Ready to start" else "No apps enabled — go to Apps tab first",
                        fontSize = 11.sp,
                        color = if (hasEnabledApps) c.green.copy(0.8f) else c.red.copy(0.85f)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isRunning) PhoenixButton("Stop", c.red, onClick = onStop)
            else           PhoenixButton("Start", c.green, onClick = onStart)
        }
    }
}

// ── App list item ─────────────────────────────────────────────────────────────

@Composable
fun AppListItem(app: ApplicationInfo, config: AppConfig?, onClick: () -> Unit) {
    val context   = LocalContext.current
    val c         = LocalAppColors.current
    val label     = remember(app.packageName) { context.packageManager.getApplicationLabel(app).toString() }
    val isEnabled = config?.isFilteringEnabled == true

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(52.dp).background(
            Brush.verticalGradient(
                if (isEnabled) listOf(PhoenixFlame, c.green) else listOf(c.borderMid, c.borderFaint)
            ), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(38.dp).background(c.cardAlt, PhoenixShapeSmall)
                .border(0.5.dp, if (isEnabled) PhoenixFlame.copy(0.3f) else c.borderMid, PhoenixShapeSmall)
                .clip(PhoenixShapeSmall)
        ) {
            AppIconImage(app.packageName, Modifier.fillMaxSize().padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isEnabled) { EmberChip("Active", PhoenixFlame); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.Default.ChevronRight, null, tint = c.borderBright, modifier = Modifier.size(16.dp))
    }
    Box(Modifier.fillMaxWidth().padding(start = 31.dp).height(0.5.dp).background(c.borderFaint))
}

@Composable
fun AppIconImage(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update  = { view ->
            try { view.setImageDrawable(context.packageManager.getApplicationIcon(packageName)) }
            catch (_: Exception) { view.setImageResource(android.R.drawable.sym_def_app_icon) }
        },
        modifier = modifier
    )
}

// ── Filter mode card ──────────────────────────────────────────────────────────

@Composable
fun FilterModeCard(currentMode: FilterMode, onModeChange: (FilterMode) -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(c.card, PhoenixShapeMedium)
            .border(1.dp, PhoenixFlame.copy(0.25f), PhoenixShapeMedium)
            .padding(16.dp)
    ) {
        Column {
            Text("Filter Mode", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PhoenixFlame)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FilterMode.BLACKLIST to c.red, FilterMode.WHITELIST to c.green)
                    .forEach { (mode, color) ->
                        val selected = currentMode == mode
                        Box(
                            Modifier.weight(1f)
                                .background(if (selected) color.copy(0.12f) else c.cardAlt, PhoenixShapeSmall)
                                .border(1.dp, if (selected) color.copy(0.6f) else c.borderMid, PhoenixShapeSmall)
                                .clickable { onModeChange(mode) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (selected) color else c.textSecondary)
                                Text(if (mode == FilterMode.BLACKLIST) "Block matched, allow rest"
                                else "Allow matched, block rest",
                                    fontSize = 10.sp,
                                    color = if (selected) color.copy(0.6f) else c.textTertiary)
                            }
                        }
                    }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (currentMode == FilterMode.BLACKLIST)
                    "All traffic is permitted unless matched by a block rule"
                else "All traffic is denied unless matched by an allow rule",
                fontSize = 10.sp, color = c.textSecondary.copy(0.7f)
            )
        }
    }
}

// ── Global rules tab ──────────────────────────────────────────────────────────

@Composable
fun GlobalRulesTab(
    rules: List<FilterRule>, filterMode: FilterMode,
    context: android.content.Context, dao: AppDao, scope: CoroutineScope
) {
    Column(Modifier.fillMaxSize()) {
        FilterModeCard(filterMode) { VpnTrackerService.setFilterMode(context, it) }
        if (rules.isEmpty()) {
            PhoenixEmptyState(Icons.Default.Lock,
                if (filterMode == FilterMode.BLACKLIST)
                    "No block rules defined\nAll traffic is permitted\nTap + to add a rule"
                else "No allow rules defined\nAll traffic is blocked\nTap + to add a rule")
        } else {
            RulesListContent(rules, dao, scope)
        }
    }
}

@Composable
fun RulesListContent(rules: List<FilterRule>, dao: AppDao, scope: CoroutineScope) {
    val c = LocalAppColors.current
    LazyColumn {
        val allow = rules.filter { it.action == RuleAction.ALLOW }
        val block  = rules.filter { it.action == RuleAction.BLOCK }
        if (allow.isNotEmpty()) {
            item { PhoenixSectionHeader("Allow List", c.green) }
            items(allow, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } },
                    onEdit   = { updated -> scope.launch { dao.updateRule(updated) } })
            }
        }
        if (block.isNotEmpty()) {
            item { PhoenixSectionHeader("Block List", c.red) }
            items(block, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } },
                    onEdit   = { updated -> scope.launch { dao.updateRule(updated) } })
            }
        }
    }
}

// ── Log lists ─────────────────────────────────────────────────────────────────

@Composable
fun GlobalLogList(
    logs: List<ConnectionLog>, globalRules: List<FilterRule>,
    filterMode: FilterMode, dao: AppDao, scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        PhoenixEmptyState(Icons.Default.List, "No DNS history yet\nStart the firewall to begin monitoring")
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            LogItemExtended(
                log        = log,
                rules      = globalRules,
                filterMode = filterMode,
                onAddRule  = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = null)) } }
            )
        }
    }
}

@Composable
fun AppTrafficList(
    logs: List<ConnectionLog>, allRules: List<FilterRule>,
    filterMode: FilterMode, packageName: String, dao: AppDao, scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        PhoenixEmptyState(Icons.Default.Info,
            "No DNS activity yet\nEnable firewall for this app\nthen start the engine")
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            LogItemExtended(
                log        = log,
                rules      = allRules,
                filterMode = filterMode,
                onAddRule  = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
            )
        }
    }
}

// ── Log item ──────────────────────────────────────────────────────────────────
// Shows a chip derived from the live rules list (updates instantly after Add Rule).
// "Add Rule" opens a pre-filled dialog; the chip replaces it on the next recomposition.

private val logDateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

@Composable
fun LogItemExtended(
    log: ConnectionLog,
    rules: List<FilterRule>,
    filterMode: FilterMode,
    onAddRule: (FilterRule) -> Unit
) {
    val c = LocalAppColors.current

    // Derived from live rules list — updates the moment a rule is inserted
    val matchingRule = findMatchingRule(log.domain, rules)

    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddRuleDialog(
            title          = "Add Rule",
            initialPattern = log.domain,
            initialMatchType = MatchType.SUBDOMAIN,
            initialAction  = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss      = { showAddDialog = false },
            onAdd          = { rule -> onAddRule(rule) }
        )
    }

    val accentColor = when (matchingRule?.action) {
        RuleAction.BLOCK -> c.red
        RuleAction.ALLOW -> c.green
        null             -> c.borderMid
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(2.dp).height(44.dp)
            .background(accentColor.copy(if (matchingRule != null) 0.8f else 0.2f), RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(log.domain, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                fontSize = 12.sp, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(remember(log.timestamp) { logDateFormat.format(Date(log.timestamp)) },
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = c.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        when (matchingRule?.action) {
            RuleAction.BLOCK -> EmberChip("Blocked", c.red)
            RuleAction.ALLOW -> EmberChip("Allowed", c.green)
            null -> EmberTextButton("Add Rule", PhoenixFlame) { showAddDialog = true }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 30.dp).height(0.5.dp).background(c.borderFaint))
}

// ── Rule item (with edit) ─────────────────────────────────────────────────────

@Composable
fun RuleItem(
    rule: FilterRule,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: (FilterRule) -> Unit
) {
    val c     = LocalAppColors.current
    val color = if (rule.action == RuleAction.BLOCK) c.red else c.green

    var showEditDialog by remember { mutableStateOf(false) }

    if (showEditDialog) {
        AddRuleDialog(
            title            = "Edit Rule",
            initialPattern   = rule.pattern,
            initialMatchType = rule.matchType,
            initialAction    = rule.action,
            onDismiss        = { showEditDialog = false },
            onAdd            = { updated ->
                // Preserve id, packageName, isEnabled from the original rule
                onEdit(updated.copy(id = rule.id, packageName = rule.packageName, isEnabled = rule.isEnabled))
            }
        )
    }

    Row(
        Modifier.fillMaxWidth().alpha(if (rule.isEnabled) 1f else 0.35f)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(2.dp).height(50.dp)
            .background(color.copy(if (rule.isEnabled) 0.8f else 0.3f), RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberChip(rule.matchType.displayName(), color)
                Spacer(Modifier.width(8.dp))
                Text(rule.pattern, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text("${if (rule.action == RuleAction.BLOCK) "Block" else "Allow"} · ${rule.matchType.description()}",
                fontSize = 10.sp, color = c.textSecondary)
        }
        Switch(
            checked = rule.isEnabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.void, checkedTrackColor = color.copy(0.8f),
                uncheckedThumbColor = c.textTertiary, uncheckedTrackColor = c.cardAlt
            )
        )
        // Edit
        IconButton(onClick = { showEditDialog = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, "Edit", tint = PhoenixFlame.copy(0.6f), modifier = Modifier.size(16.dp))
        }
        // Delete
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete", tint = c.red.copy(0.6f), modifier = Modifier.size(16.dp))
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 30.dp).height(0.5.dp).background(c.borderFaint))
}

// ── App detail screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(navController: NavController, dao: AppDao, packageName: String) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val c       = LocalAppColors.current

    var navigatingBack by remember { mutableStateOf(false) }
    val onBack: () -> Unit = {
        if (!navigatingBack) { navigatingBack = true; navController.popBackStack() }
    }
    BackHandler(onBack = onBack)

    val label = remember(packageName) {
        try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) { packageName }
    }

    val configs       by dao.getAllAppConfigs().collectAsState(initial = emptyList())
    val appConfig     = configs.find { it.packageName == packageName }
    val appRules      by dao.getAppRules(packageName).collectAsState(initial = emptyList())
    val globalRules   by dao.getGlobalRules().collectAsState(initial = emptyList())
    val allLogs       by dao.getRecentLogs().collectAsState(initial = emptyList())
    val filterMode    by VpnTrackerService.filterMode.collectAsState()
    val combinedRules = remember(appRules, globalRules) { appRules + globalRules }

    var selectedSection     by remember { mutableIntStateOf(0) }
    var showAddRuleDialog   by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var logSearchQuery      by remember { mutableStateOf("") }

    val filteredLogs = remember(allLogs, logSearchQuery) {
        if (logSearchQuery.isBlank()) allLogs
        else allLogs.filter { it.domain.contains(logSearchQuery, ignoreCase = true) }
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            title = "Add Rule — $label",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss = { showAddRuleDialog = false },
            onAdd = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
        )
    }
    if (showClearLogsDialog) {
        PhoenixAlertDialog(
            title = "Clear Logs", text = "All DNS activity logs will be permanently deleted.",
            confirmText = "Clear", confirmColor = c.red,
            onConfirm = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss = { showClearLogsDialog = false }
        )
    }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Box(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = PhoenixFlame) }
                    Box(
                        Modifier.size(32.dp).background(c.cardAlt, PhoenixShapeSmall)
                            .border(0.5.dp, PhoenixFlame.copy(0.3f), PhoenixShapeSmall).clip(PhoenixShapeSmall)
                    ) { AppIconImage(packageName, Modifier.fillMaxSize().padding(3.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = PhoenixFlame,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(packageName, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomStart)
                    .background(Brush.horizontalGradient(
                        listOf(PhoenixFlame.copy(0.5f), PhoenixFlame.copy(0.1f), Color.Transparent))))
            }
        },
        floatingActionButton = {
            when (selectedSection) {
                0 -> AnimatedVisibility(allLogs.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    PhoenixFab(onClick = { showClearLogsDialog = true }, icon = Icons.Default.Delete,
                        color = c.red, contentDescription = "Clear logs")
                }
                1 -> PhoenixFab(onClick = { showAddRuleDialog = true }, icon = Icons.Default.Add,
                    color = PhoenixFlame, contentDescription = "Add rule")
                else -> {}
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(c.background)) {
            // Firewall toggle
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(c.card, PhoenixShapeMedium)
                    .border(1.dp,
                        if (appConfig?.isFilteringEnabled == true) c.green.copy(0.35f) else c.borderMid,
                        PhoenixShapeMedium)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Firewall Intercept", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PhoenixFlame)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (appConfig?.isFilteringEnabled == true) "DNS filtering is active"
                            else "App bypasses the firewall",
                            fontSize = 11.sp,
                            color = if (appConfig?.isFilteringEnabled == true) c.green.copy(0.8f) else c.textSecondary
                        )
                    }
                    Switch(
                        checked = appConfig?.isFilteringEnabled ?: false,
                        onCheckedChange = { enabled ->
                            scope.launch { dao.insertAppConfig(AppConfig(packageName, label, enabled)) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.void, checkedTrackColor = c.green.copy(0.8f),
                            uncheckedThumbColor = c.textTertiary, uncheckedTrackColor = c.cardAlt
                        )
                    )
                }
            }

            // Tab selector
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(c.card, PhoenixShapeSmall)
                    .border(1.dp, c.borderMid, PhoenixShapeSmall).padding(4.dp)
            ) {
                listOf("DNS Activity", "App Rules").forEachIndexed { idx, tabLabel ->
                    val selected = selectedSection == idx
                    val display  = if (idx == 1 && appRules.isNotEmpty()) "$tabLabel  ${appRules.size}" else tabLabel
                    Box(
                        Modifier.weight(1f)
                            .background(if (selected) PhoenixFlameGhost else Color.Transparent, PhoenixShapeSmall)
                            .border(if (selected) 1.dp else 0.dp,
                                if (selected) PhoenixFlame.copy(0.4f) else Color.Transparent, PhoenixShapeSmall)
                            .clickable { selectedSection = idx }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(display, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            color = if (selected) PhoenixFlame else c.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (selectedSection == 0) {
                PhoenixSearchField(logSearchQuery, { logSearchQuery = it }, "Search domains...")
                if (logSearchQuery.isNotBlank()) {
                    Text("${filteredLogs.size} of ${allLogs.size} results",
                        fontSize = 11.sp, color = PhoenixFlameDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                }
                AppTrafficList(filteredLogs, combinedRules, filterMode, packageName, dao, scope)
            } else {
                AppRulesList(appRules, label, globalRules.size, dao, scope)
            }
        }
    }
}

// ── App rules list ────────────────────────────────────────────────────────────

@Composable
fun AppRulesList(rules: List<FilterRule>, appLabel: String, globalCount: Int, dao: AppDao, scope: CoroutineScope) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize()) {
        if (globalCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(c.goldGhost, PhoenixShapeSmall)
                    .border(1.dp, c.gold.copy(0.25f), PhoenixShapeSmall).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = c.gold)
                Spacer(Modifier.width(8.dp))
                Text("$globalCount global rule${if (globalCount != 1) "s" else ""} also apply to this app",
                    fontSize = 11.sp, color = c.gold.copy(0.9f))
            }
        }
        if (rules.isEmpty()) {
            PhoenixEmptyState(Icons.Default.Lock,
                "No app-specific rules\nTap + to add one\nor tap Add Rule in DNS Activity")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val allow = rules.filter { it.action == RuleAction.ALLOW }
                val block  = rules.filter { it.action == RuleAction.BLOCK }
                if (allow.isNotEmpty()) {
                    item { PhoenixSectionHeader("Allow Rules", c.green) }
                    items(allow, key = { it.id }) { rule ->
                        RuleItem(rule,
                            onDelete = { scope.launch { dao.deleteRule(rule) } },
                            onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } },
                            onEdit   = { updated -> scope.launch { dao.updateRule(updated) } })
                    }
                }
                if (block.isNotEmpty()) {
                    item { PhoenixSectionHeader("Block Rules", c.red) }
                    items(block, key = { it.id }) { rule ->
                        RuleItem(rule,
                            onDelete = { scope.launch { dao.deleteRule(rule) } },
                            onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } },
                            onEdit   = { updated -> scope.launch { dao.updateRule(updated) } })
                    }
                }
            }
        }
    }
}

// ── Add / Edit rule dialog ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRuleDialog(
    title: String,
    initialPattern:   String     = "",
    initialMatchType: MatchType  = MatchType.SUBDOMAIN,
    initialAction:    RuleAction = RuleAction.BLOCK,
    onDismiss: () -> Unit,
    onAdd: (FilterRule) -> Unit
) {
    val c = LocalAppColors.current
    // remember keyed to initial values so re-opening for different rules resets state
    var pattern      by remember(initialPattern)   { mutableStateOf(initialPattern) }
    var matchType    by remember(initialMatchType) { mutableStateOf(initialMatchType) }
    var action       by remember(initialAction)    { mutableStateOf(initialAction) }
    var menuExpanded by remember { mutableStateOf(false) }
    val trimmed = pattern.trim().lowercase()

    // Live wildcard preview — recomputes on every keystroke
    val wildcardExamples = remember(matchType, trimmed) {
        if (matchType == MatchType.WILDCARD) generateWildcardExamples(trimmed) else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.surface,
        tonalElevation   = 0.dp,
        shape            = PhoenixShapeLarge,
        title = { Text(title, fontWeight = FontWeight.Bold, color = PhoenixFlame) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Block / Allow toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RuleAction.BLOCK to c.red, RuleAction.ALLOW to c.green).forEach { (a, color) ->
                        Box(
                            Modifier.weight(1f)
                                .background(if (action == a) color.copy(0.12f) else c.card, PhoenixShapeSmall)
                                .border(1.dp, if (action == a) color.copy(0.7f) else c.borderMid, PhoenixShapeSmall)
                                .clickable { action = a }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(a.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                color = if (action == a) color else c.textSecondary)
                        }
                    }
                }

                // Pattern input — placeholder adapts to match type
                OutlinedTextField(
                    value = pattern, onValueChange = { pattern = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pattern", fontSize = 12.sp) },
                    placeholder = {
                        Text(
                            when (matchType) {
                                MatchType.WILDCARD  -> "*.example.com"
                                MatchType.PREFIX    -> "ads."
                                MatchType.SUFFIX    -> ".doubleclick.net"
                                MatchType.CONTAINS  -> "tracker"
                                MatchType.EXACT     -> "ads.example.com"
                                MatchType.SUBDOMAIN -> "example.com"
                            },
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.textTertiary
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = PhoenixFlame.copy(0.6f),
                        unfocusedBorderColor = c.borderMid,
                        focusedTextColor     = c.textPrimary, unfocusedTextColor = c.textPrimary,
                        cursorColor          = PhoenixFlame,
                        focusedLabelColor    = PhoenixFlame, unfocusedLabelColor = c.textSecondary
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )

                // Wildcard live preview — appears only when type=WILDCARD and * is present
                AnimatedVisibility(visible = wildcardExamples.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(PhoenixFlameGhost, PhoenixShapeSmall)
                            .border(0.5.dp, PhoenixFlame.copy(0.35f), PhoenixShapeSmall)
                            .padding(10.dp)
                    ) {
                        Text("Pattern matches e.g:", fontSize = 10.sp, color = PhoenixFlameDim)
                        Spacer(Modifier.height(4.dp))
                        wildcardExamples.forEach { ex ->
                            Text("→ $ex", fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium, fontSize = 11.sp, color = c.textPrimary)
                        }
                    }
                }

                // Match type dropdown
                ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                    OutlinedTextField(
                        value = "${matchType.displayName()} — ${matchType.description()}",
                        onValueChange = {}, readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Match Type", fontSize = 12.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = PhoenixFlame.copy(0.6f),
                            unfocusedBorderColor = c.borderMid,
                            focusedTextColor     = c.textPrimary, unfocusedTextColor = c.textPrimary,
                            focusedLabelColor    = PhoenixFlame, unfocusedLabelColor = c.textSecondary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                    ExposedDropdownMenu(
                        expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(c.card)
                    ) {
                        MatchType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName(), fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp, color = PhoenixFlame)
                                        Text(type.description(), fontSize = 11.sp, color = c.textSecondary)
                                        // Extra hint for wildcard
                                        if (type == MatchType.WILDCARD) {
                                            Spacer(Modifier.height(2.dp))
                                            Text("e.g. *.ads.com  •  tracker.*  •  *doubleclick*",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp, color = PhoenixFlameDim)
                                        }
                                    }
                                },
                                onClick = { matchType = type; menuExpanded = false },
                                colors  = MenuDefaults.itemColors(textColor = c.textPrimary)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val btnColor = if (action == RuleAction.BLOCK) c.red else c.green
            PhoenixButton(
                text    = if (action == RuleAction.BLOCK) "Block" else "Allow",
                color   = btnColor,
                enabled = trimmed.isNotBlank(),
                onClick = {
                    onAdd(FilterRule(packageName = null, pattern = trimmed, matchType = matchType, action = action))
                    onDismiss()
                }
            )
        },
        dismissButton = { PhoenixButton("Cancel", c.textSecondary, onClick = onDismiss) }
    )
}

// ── Alert dialog ──────────────────────────────────────────────────────────────

@Composable
fun PhoenixAlertDialog(
    title: String, text: String, confirmText: String,
    confirmColor: Color, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.surface, tonalElevation = 0.dp, shape = PhoenixShapeLarge,
        title  = { Text(title, fontWeight = FontWeight.Bold, color = confirmColor) },
        text   = { Text(text, fontSize = 13.sp, color = c.textSecondary) },
        confirmButton = { PhoenixButton(confirmText, confirmColor, onClick = onConfirm) },
        dismissButton = { PhoenixButton("Cancel", c.textSecondary, onClick = onDismiss) }
    )
}

// ── Primitive components ──────────────────────────────────────────────────────

@Composable
fun PhoenixButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.alpha(if (enabled) 1f else 0.4f)
            .background(color.copy(if (enabled) 0.12f else 0.05f), PhoenixShapeSmall)
            .border(1.dp, color.copy(if (enabled) 0.65f else 0.2f), PhoenixShapeSmall)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = color.copy(if (enabled) 1f else 0.4f))
    }
}

@Composable
fun EmberTextButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.border(0.5.dp, color.copy(0.45f), PhoenixShapeChip)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = color)
    }
}

@Composable
fun PhoenixFab(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color, contentDescription: String
) {
    Box(
        Modifier.size(52.dp).background(color.copy(0.12f), PhoenixShapeMedium)
            .border(1.dp, color.copy(0.65f), PhoenixShapeMedium).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PhoenixEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(52.dp), tint = PhoenixFlame.copy(0.15f))
        Spacer(Modifier.height(20.dp))
        Text(message, textAlign = TextAlign.Center, fontSize = 13.sp,
            lineHeight = 20.sp, color = c.textSecondary.copy(0.7f))
    }
}