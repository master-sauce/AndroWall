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

// ── Entry point ───────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreference.load(this)

        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (ThemePreference.isDark.value) android.graphics.Color.parseColor("#0D0B09")
                else android.graphics.Color.parseColor("#FAF6F2")
            )
        )

        enableEdgeToEdge()
        setContent { AndroWallTheme { AndroWallApp() } }
    }

    override fun onResume() {
        super.onResume()
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (ThemePreference.isDark.value) android.graphics.Color.parseColor("#0D0B09")
                else android.graphics.Color.parseColor("#FAF6F2")
            )
        )
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
                AppDetailScreen(
                    navController, dao,
                    back.arguments?.getString("packageName") ?: ""
                )
            }
        }
    }
}

// ── Domain matching helpers ───────────────────────────────────────────────────

fun isEffectivelyBlocked(domain: String, rules: List<FilterRule>, mode: FilterMode): Boolean {
    val lower = domain.lowercase()
    val match = rules.filter { it.isEnabled }.firstOrNull { rule ->
        when (rule.matchType) {
            MatchType.EXACT     -> lower == rule.pattern.lowercase()
            MatchType.SUBDOMAIN -> lower == rule.pattern.lowercase() || lower.endsWith(".${rule.pattern.lowercase()}")
            MatchType.CONTAINS  -> lower.contains(rule.pattern.lowercase())
            MatchType.PREFIX    -> lower.startsWith(rule.pattern.lowercase())
            MatchType.SUFFIX    -> lower.endsWith(rule.pattern.lowercase())
            MatchType.WILDCARD  -> matchWildcard(lower, rule.pattern.lowercase())
        }
    }
    return when {
        match != null                -> match.action == RuleAction.BLOCK
        mode == FilterMode.WHITELIST -> true
        else                         -> false
    }
}

fun matchWildcard(domain: String, pattern: String): Boolean {
    val regex = try {
        val escaped = Regex.escape(pattern).replace("\\*", ".*")
        Regex("^$escaped$", RegexOption.IGNORE_CASE)
    } catch (_: Exception) { return false }
    return regex.matches(domain)
}

fun isBlockedForConnection(
    log: ConnectionLog,
    rules: List<FilterRule>,
    mode: FilterMode,
    trafficScope: VpnTrackerService.TrafficScope
): Boolean {
    val typeApplies = when (trafficScope) {
        VpnTrackerService.TrafficScope.DNS_ONLY  -> log.connectionType == ConnectionType.DNS
        VpnTrackerService.TrafficScope.HTTP_ONLY -> log.connectionType == ConnectionType.HTTP ||
                log.connectionType == ConnectionType.HTTPS
        VpnTrackerService.TrafficScope.ALL       -> true
    }
    if (!typeApplies) return false

    if (log.isBlocked) return true
    val domainMatch = isEffectivelyBlocked(log.domain, rules, mode)
    if (domainMatch) return true
    val url = log.url
    if (url != null && url.isNotBlank() && url != "https://${log.domain}" && url != "http://${log.domain}") {
        return isEffectivelyBlocked(url, rules, mode)
    }
    return false
}

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, dao: AppDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val isDark = c.isDark

    val appConfigs   by dao.getAllAppConfigs().collectAsState(initial = emptyList())
    val recentLogs   by dao.getRecentLogs().collectAsState(initial = emptyList())
    val globalRules  by dao.getGlobalRules().collectAsState(initial = emptyList())
    val filterMode   by VpnTrackerService.filterMode.collectAsState()
    val trafficScope by VpnTrackerService.trafficScope.collectAsState()

    var installedApps           by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var searchQuery             by remember { mutableStateOf("") }
    var logSearchQuery          by remember { mutableStateOf("") }
    var selectedTab             by remember { mutableIntStateOf(0) }
    var showErrorToast          by remember { mutableStateOf(false) }
    var toastMessage            by remember { mutableStateOf("") }
    var showClearLogsDialog     by remember { mutableStateOf(false) }
    var showAddGlobalRuleDialog by remember { mutableStateOf(false) }
    var prefillRulePattern      by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val vpnLauncher = rememberLauncherForActivityResult(
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
        else recentLogs.filter { log ->
            log.domain.contains(logSearchQuery, ignoreCase = true) ||
                    (log.url?.contains(logSearchQuery, ignoreCase = true) == true)
        }
    }

    if (showClearLogsDialog) {
        PhoenixAlertDialog(
            title        = "Clear History",
            text         = "All connection logs will be permanently deleted.",
            confirmText  = "Clear",
            confirmColor = c.red,
            onConfirm    = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss    = { showClearLogsDialog = false }
        )
    }
    if (showAddGlobalRuleDialog) {
        AddRuleDialog(
            title          = "Add Global Rule",
            initialAction  = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            initialPattern = prefillRulePattern,
            onDismiss      = { showAddGlobalRuleDialog = false; prefillRulePattern = "" },
            onAdd          = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = null)) } }
        )
    }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Box(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AndroWall", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = PhoenixFlame)
                    IconButton(
                        onClick = { ThemePreference.toggle(context) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                            tint = PhoenixFlame, modifier = Modifier.size(20.dp)
                        )
                    }
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
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(Brush.horizontalGradient(
                            listOf(Color.Transparent, PhoenixFlame.copy(0.35f), Color.Transparent)))
                )
                NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    listOf(
                        Triple(0, Icons.Default.Home, "Apps"),
                        Triple(1, Icons.Default.List, "Logs"),
                        Triple(2, Icons.Default.Lock, "Rules")
                    ).forEach { (idx, icon, label) ->
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
                1 -> AnimatedVisibility(
                    recentLogs.isNotEmpty(),
                    enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()
                ) {
                    PhoenixFab(
                        onClick            = { showClearLogsDialog = true },
                        icon               = Icons.Default.Delete,
                        color              = c.red,
                        contentDescription = "Clear logs"
                    )
                }
                2 -> PhoenixFab(
                    onClick            = { prefillRulePattern = ""; showAddGlobalRuleDialog = true },
                    icon               = Icons.Default.Add,
                    color              = PhoenixFlame,
                    contentDescription = "Add rule"
                )
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
                            toastMessage   = "No apps have filtering enabled.\nGo to the Apps tab and enable at least one."
                            showErrorToast = true
                            return@FirewallStatusCard
                        }
                        val intent = VpnService.prepare(context)
                        if (intent != null) vpnLauncher.launch(intent)
                        else context.startService(Intent(context, VpnTrackerService::class.java))
                    },
                    onStop = {
                        context.startService(
                            Intent(context, VpnTrackerService::class.java).apply {
                                action = VpnTrackerService.ACTION_STOP_VPN
                            }
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
                        PhoenixSearchField(logSearchQuery, { logSearchQuery = it }, "Search domains or URLs...")
                        if (logSearchQuery.isNotBlank()) {
                            Text("${filteredLogs.size} of ${recentLogs.size} results",
                                fontSize = 11.sp, color = PhoenixFlameDim,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                        }
                        GlobalLogList(filteredLogs, globalRules, filterMode, trafficScope, dao, scope)
                    }
                    2 -> GlobalRulesTab(globalRules, filterMode, context, dao, scope)
                }
            }

            FireToastCard(
                message   = toastMessage,
                visible   = showErrorToast,
                onDismiss = { showErrorToast = false },
                modifier  = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            )
        }
    }
}

// ── Search field ──────────────────────────────────────────────────────────────

@Composable
fun PhoenixSearchField(
    value: String, onValueChange: (String) -> Unit, placeholder: String
) {
    val c = LocalAppColors.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(c.card, PhoenixShapeMedium)
            .border(1.dp, c.borderMid.copy(0.4f), PhoenixShapeMedium)
    ) {
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(placeholder, fontSize = 13.sp, color = c.textSecondary) },
            leadingIcon   = {
                Icon(Icons.Default.Search, null, tint = PhoenixFlame, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (value.isNotEmpty())
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Clear, "Clear", tint = PhoenixFlameDim, modifier = Modifier.size(16.dp))
                    }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor     = c.textPrimary,
                unfocusedTextColor   = c.textPrimary,
                cursorColor          = PhoenixFlame
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
}

// ── Firewall status card ──────────────────────────────────────────────────────

@Composable
fun FirewallStatusCard(hasEnabledApps: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val isRunning   by VpnTrackerService.isRunning.collectAsState()
    val c = LocalAppColors.current
    val borderCol   = if (isRunning) PhoenixFlame else c.borderMid

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .background(c.card, PhoenixShapeLarge)
            .border(1.dp, borderCol.copy(0.4f), PhoenixShapeLarge)
            .clip(PhoenixShapeLarge)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingDot(if (isRunning) PhoenixFlame else c.textSecondary, 14.dp)
                Spacer(Modifier.height(6.dp))
                Icon(
                    if (isRunning) Icons.Default.Lock else Icons.Default.LockOpen, null,
                    tint = if (isRunning) PhoenixFlame else c.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isRunning) "Firewall Active" else "Firewall Offline",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (isRunning) PhoenixFlame else c.textSecondary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isRunning) "Monitoring DNS + HTTP/S traffic" else "Engine is stopped",
                    fontSize = 12.sp,
                    color = if (isRunning) PhoenixFlame.copy(0.65f) else c.textTertiary
                )
                if (!isRunning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasEnabledApps) "Ready to start" else "No apps enabled — go to Apps tab first",
                        fontSize = 11.sp,
                        color = if (hasEnabledApps) c.green.copy(0.7f) else c.red.copy(0.8f)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isRunning) PhoenixButton("Stop",  c.red,   onClick = onStop)
            else           PhoenixButton("Start", c.green, onClick = onStart)
        }
    }
}

// ── App list item ─────────────────────────────────────────────────────────────

@Composable
fun AppListItem(app: ApplicationInfo, config: AppConfig?, onClick: () -> Unit) {
    val context   = LocalContext.current
    val c = LocalAppColors.current
    val label = remember(app.packageName) {
        context.packageManager.getApplicationLabel(app).toString()
    }
    val isEnabled = config?.isFilteringEnabled == true

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(3.dp).height(52.dp).background(
                Brush.verticalGradient(
                    if (isEnabled) listOf(PhoenixFlame, c.green)
                    else listOf(c.borderMid, c.borderFaint)
                ), RoundedCornerShape(2.dp)
            )
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(38.dp).background(c.cardAlt, PhoenixShapeSmall)
                .border(0.5.dp,
                    if (isEnabled) PhoenixFlame.copy(0.3f) else c.borderMid,
                    PhoenixShapeSmall)
                .clip(PhoenixShapeSmall)
        ) {
            AppIconImage(app.packageName, Modifier.fillMaxSize().padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isEnabled) {
            EmberChip("Active", PhoenixFlame)
            Spacer(Modifier.width(8.dp))
        }
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
    val context = LocalContext.current
    val trafficScope by VpnTrackerService.trafficScope.collectAsState()

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
                                .clickable { onModeChange(mode) }.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text(
                                    mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                    color = if (selected) color else c.textSecondary
                                )
                                Text(
                                    if (mode == FilterMode.BLACKLIST) "Block matched, allow rest"
                                    else "Allow matched, block rest",
                                    fontSize = 10.sp,
                                    color = if (selected) color.copy(0.6f) else c.textTertiary
                                )
                            }
                        }
                    }
            }

            Spacer(Modifier.height(14.dp))
            Text("Traffic Scope", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PhoenixFlame)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple(VpnTrackerService.TrafficScope.DNS_ONLY,  "DNS",   "Domain queries only"),
                    Triple(VpnTrackerService.TrafficScope.HTTP_ONLY, "HTTP/S", "Web URLs & SNI"),
                    Triple(VpnTrackerService.TrafficScope.ALL,       "All",   "DNS + HTTP/S traffic")
                ).forEach { (scope, label, desc) ->
                    val selected = trafficScope == scope
                    Box(
                        Modifier.weight(1f)
                            .background(
                                if (selected) PhoenixFlame.copy(0.12f) else c.cardAlt,
                                PhoenixShapeSmall
                            )
                            .border(
                                1.dp,
                                if (selected) PhoenixFlame.copy(0.65f) else c.borderMid,
                                PhoenixShapeSmall
                            )
                            .clickable { VpnTrackerService.setTrafficScope(context, scope) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label,
                                fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                color = if (selected) PhoenixFlame else c.textSecondary
                            )
                            Text(
                                desc,
                                fontSize = 9.sp, textAlign = TextAlign.Center,
                                color = if (selected) PhoenixFlame.copy(0.7f) else c.textTertiary,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when (trafficScope) {
                    VpnTrackerService.TrafficScope.DNS_ONLY  -> "Rules apply to DNS domain lookups only"
                    VpnTrackerService.TrafficScope.HTTP_ONLY -> "Rules apply to HTTP/HTTPS URLs and SNI hostnames only"
                    VpnTrackerService.TrafficScope.ALL       -> "Rules apply to both DNS queries and HTTP/HTTPS traffic"
                },
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
            PhoenixEmptyState(
                Icons.Default.Lock,
                if (filterMode == FilterMode.BLACKLIST)
                    "No block rules defined\nAll traffic is permitted\nTap + to add a rule"
                else "No allow rules defined\nAll traffic is blocked\nTap + to add a rule"
            )
        } else {
            RulesListContent(rules, dao, scope)
        }
    }
}

@Composable
fun RulesListContent(rules: List<FilterRule>, dao: AppDao, scope: CoroutineScope) {
    LazyColumn {
        val allow = rules.filter { it.action == RuleAction.ALLOW }
        val block = rules.filter { it.action == RuleAction.BLOCK }
        if (allow.isNotEmpty()) {
            item { PhoenixSectionHeader("Allow List", LocalAppColors.current.green) }
            items(allow, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
            }
        }
        if (block.isNotEmpty()) {
            item { PhoenixSectionHeader("Block List", LocalAppColors.current.red) }
            items(block, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
            }
        }
    }
}

// ── Log lists ─────────────────────────────────────────────────────────────────

@Composable
fun GlobalLogList(
    logs: List<ConnectionLog>,
    globalRules: List<FilterRule>,
    filterMode: FilterMode,
    trafficScope: VpnTrackerService.TrafficScope,
    dao: AppDao,
    scope: CoroutineScope
) {
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var prefillPattern    by remember { mutableStateOf("") }

    if (logs.isEmpty()) {
        PhoenixEmptyState(Icons.Default.List, "No DNS or HTTP/S history yet\nStart the firewall to begin monitoring")
        return
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            title          = "Add Global Rule",
            initialAction  = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            initialPattern = prefillPattern,
            onDismiss      = { showAddRuleDialog = false; prefillPattern = "" },
            onAdd          = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = null)) } }
        )
    }

    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = isBlockedForConnection(log, globalRules, filterMode, trafficScope)
            LogItemExtended(log, blocked, filterMode,
                onAddRule = {
                    prefillPattern = log.url?.takeIf {
                        it.isNotBlank() && it != "https://${log.domain}" && it != "http://${log.domain}"
                    } ?: log.domain
                    showAddRuleDialog = true
                })
        }
    }
}

@Composable
fun AppTrafficList(
    logs: List<ConnectionLog>,
    allRules: List<FilterRule>,
    filterMode: FilterMode,
    trafficScope: VpnTrackerService.TrafficScope,
    packageName: String,
    dao: AppDao,
    scope: CoroutineScope
) {
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var prefillPattern    by remember { mutableStateOf("") }

    if (logs.isEmpty()) {
        PhoenixEmptyState(Icons.Default.Info, "No DNS or HTTP/S activity yet\nEnable firewall for this app\nthen start the engine")
        return
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            title          = "Add App Rule",
            initialAction  = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            initialPattern = prefillPattern,
            onDismiss      = { showAddRuleDialog = false; prefillPattern = "" },
            onAdd          = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
        )
    }

    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = isBlockedForConnection(log, allRules, filterMode, trafficScope)
            LogItemExtended(log, blocked, filterMode,
                onAddRule = {
                    prefillPattern = log.url?.takeIf {
                        it.isNotBlank() && it != "https://${log.domain}" && it != "http://${log.domain}"
                    } ?: log.domain
                    showAddRuleDialog = true
                })
        }
    }
}

// ── Log item ──────────────────────────────────────────────────────────────────

private val logDateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

@Composable
fun LogItemExtended(
    log: ConnectionLog, isBlocked: Boolean, filterMode: FilterMode,
    onAddRule: () -> Unit
) {
    val c = LocalAppColors.current

    val accentColor = when {
        isBlocked                          -> c.red
        filterMode == FilterMode.WHITELIST -> c.green
        else                               -> c.borderMid
    }

    val typeColor = when (log.connectionType) {
        ConnectionType.DNS   -> c.gold
        ConnectionType.HTTP  -> c.green
        ConnectionType.HTTPS -> PhoenixFlame
        else                 -> PhoenixFlame
    }

    val hasExtraUrl = log.url != null &&
            log.url != "https://${log.domain}" && log.url != "http://${log.domain}"

    val showAddRuleButton = when {
        filterMode == FilterMode.BLACKLIST && !isBlocked -> true
        filterMode == FilterMode.WHITELIST && isBlocked  -> true
        else -> false
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(2.dp).height(if (hasExtraUrl) 56.dp else 44.dp)
                .background(
                    accentColor.copy(if (isBlocked || filterMode == FilterMode.WHITELIST) 0.8f else 0.2f),
                    RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberChip(log.connectionType.name, typeColor, modifier = Modifier.padding(end = 6.dp))
                Text(log.domain,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    fontSize = 12.sp, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasExtraUrl) {
                Text(log.url, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                    color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(remember(log.timestamp) { logDateFormat.format(Date(log.timestamp)) },
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = c.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        when {
            showAddRuleButton -> EmberTextButton("Add Rule", PhoenixFlame, onAddRule)
            isBlocked         -> EmberChip("Blocked", c.red)
            else              -> EmberChip("Allowed", c.green)
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 30.dp).height(0.5.dp).background(c.borderFaint))
}

// ── Rule item ─────────────────────────────────────────────────────────────────

@Composable
fun RuleItem(rule: FilterRule, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    val color = if (rule.action == RuleAction.BLOCK) c.red else c.green
    val scopeColor = when (rule.scope) {
        RuleScope.DNS -> c.gold
        RuleScope.URL -> PhoenixFlame
        RuleScope.ANY -> color
    }

    Row(
        Modifier.fillMaxWidth()
            .alpha(if (rule.isEnabled) 1f else 0.35f)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(2.dp).height(50.dp)
                .background(color.copy(if (rule.isEnabled) 0.8f else 0.3f), RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberChip(rule.matchType.displayName(), color)
                if (rule.scope != RuleScope.ANY) {
                    Spacer(Modifier.width(4.dp))
                    EmberChip(rule.scope.name, scopeColor)
                }
                Spacer(Modifier.width(8.dp))
                Text(rule.pattern,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${if (rule.action == RuleAction.BLOCK) "Block" else "Allow"} · ${rule.matchType.description()} · ${rule.scope.name}",
                fontSize = 10.sp, color = c.textSecondary
            )
        }
        Switch(
            checked         = rule.isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = c.void, checkedTrackColor   = color.copy(0.8f),
                uncheckedThumbColor = c.textTertiary, uncheckedTrackColor = c.cardAlt
            )
        )
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
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val isDark = c.isDark

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
    val trafficScope  by VpnTrackerService.trafficScope.collectAsState()
    val combinedRules = remember(appRules, globalRules) { appRules + globalRules }

    var selectedSection     by remember { mutableIntStateOf(0) }
    var showAddRuleDialog   by remember { mutableStateOf(false) }
    var prefillRulePattern  by remember { mutableStateOf("") }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var logSearchQuery      by remember { mutableStateOf("") }

    val filteredLogs = remember(allLogs, logSearchQuery) {
        if (logSearchQuery.isBlank()) allLogs
        else allLogs.filter { log ->
            log.domain.contains(logSearchQuery, ignoreCase = true) ||
                    (log.url?.contains(logSearchQuery, ignoreCase = true) == true)
        }
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            title          = "Add Rule — $label",
            initialAction  = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            initialPattern = prefillRulePattern,
            onDismiss      = { showAddRuleDialog = false; prefillRulePattern = "" },
            onAdd          = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
        )
    }
    if (showClearLogsDialog) {
        PhoenixAlertDialog(
            title        = "Clear Logs",
            text         = "All DNS and HTTP/S activity logs will be permanently deleted.",
            confirmText  = "Clear",
            confirmColor = c.red,
            onConfirm    = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss    = { showClearLogsDialog = false }
        )
    }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Box(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = PhoenixFlame)
                    }
                    Box(
                        Modifier.size(32.dp).background(c.cardAlt, PhoenixShapeSmall)
                            .border(0.5.dp, PhoenixFlame.copy(0.3f), PhoenixShapeSmall)
                            .clip(PhoenixShapeSmall)
                    ) {
                        AppIconImage(packageName, Modifier.fillMaxSize().padding(3.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = PhoenixFlame,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(packageName, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { ThemePreference.toggle(context) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                            tint = PhoenixFlame, modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomStart)
                        .background(Brush.horizontalGradient(
                            listOf(PhoenixFlame.copy(0.5f), PhoenixFlame.copy(0.1f), Color.Transparent)))
                )
            }
        },
        floatingActionButton = {
            when (selectedSection) {
                0 -> AnimatedVisibility(
                    allLogs.isNotEmpty(),
                    enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()
                ) {
                    PhoenixFab(onClick = { showClearLogsDialog = true },
                        icon = Icons.Default.Delete, color = c.red, contentDescription = "Clear logs")
                }
                1 -> PhoenixFab(
                    onClick = { prefillRulePattern = ""; showAddRuleDialog = true },
                    icon = Icons.Default.Add, color = PhoenixFlame, contentDescription = "Add rule"
                )
                else -> {}
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(c.background)) {
            // Firewall toggle card
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(c.card, PhoenixShapeMedium)
                    .border(
                        1.dp,
                        if (appConfig?.isFilteringEnabled == true) c.green.copy(0.35f) else c.borderMid,
                        PhoenixShapeMedium
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Firewall Intercept",
                            fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PhoenixFlame)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (appConfig?.isFilteringEnabled == true) "DNS + HTTP/S monitoring is active"
                            else "App bypasses the firewall",
                            fontSize = 11.sp,
                            color = if (appConfig?.isFilteringEnabled == true)
                                c.green.copy(0.7f) else c.textSecondary
                        )
                    }
                    Switch(
                        checked         = appConfig?.isFilteringEnabled ?: false,
                        onCheckedChange = { enabled ->
                            scope.launch { dao.insertAppConfig(AppConfig(packageName, label, enabled)) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = c.void,
                            checkedTrackColor   = c.green.copy(0.8f),
                            uncheckedThumbColor = c.textTertiary,
                            uncheckedTrackColor = c.cardAlt
                        )
                    )
                }
            }

            // Tab selector
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(c.card, PhoenixShapeSmall)
                    .border(1.dp, c.borderMid, PhoenixShapeSmall)
                    .padding(4.dp)
            ) {
                listOf("Activity", "App Rules").forEachIndexed { idx, tabLabel ->
                    val selected = selectedSection == idx
                    val display  = if (idx == 1 && appRules.isNotEmpty()) "$tabLabel  ${appRules.size}" else tabLabel
                    Box(
                        Modifier.weight(1f)
                            .background(if (selected) PhoenixFlameGhost else Color.Transparent, PhoenixShapeSmall)
                            .border(
                                if (selected) 1.dp else 0.dp,
                                if (selected) PhoenixFlame.copy(0.4f) else Color.Transparent,
                                PhoenixShapeSmall
                            )
                            .clickable { selectedSection = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(display, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            color = if (selected) PhoenixFlame else c.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (selectedSection == 0) {
                PhoenixSearchField(logSearchQuery, { logSearchQuery = it }, "Search domains or URLs...")
                if (logSearchQuery.isNotBlank()) {
                    Text("${filteredLogs.size} of ${allLogs.size} results",
                        fontSize = 11.sp, color = PhoenixFlameDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                }
                AppTrafficList(filteredLogs, combinedRules, filterMode, trafficScope, packageName, dao, scope)
            } else {
                AppRulesList(appRules, label, globalRules.size, dao, scope)
            }
        }
    }
}

// ── App rules list ────────────────────────────────────────────────────────────

@Composable
fun AppRulesList(
    rules: List<FilterRule>, appLabel: String, globalCount: Int,
    dao: AppDao, scope: CoroutineScope
) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxSize()) {
        if (globalCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(c.goldGhost, PhoenixShapeSmall)
                    .border(1.dp, c.gold.copy(0.25f), PhoenixShapeSmall)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = c.gold)
                Spacer(Modifier.width(8.dp))
                Text("$globalCount global rule${if (globalCount != 1) "s" else ""} also apply to this app",
                    fontSize = 11.sp, color = c.gold.copy(0.85f))
            }
        }
        if (rules.isEmpty()) {
            PhoenixEmptyState(Icons.Default.Lock, "No app-specific rules\nTap + to add one\nor tap Add Rule in Activity")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val allow = rules.filter { it.action == RuleAction.ALLOW }
                val block = rules.filter { it.action == RuleAction.BLOCK }
                if (allow.isNotEmpty()) {
                    item { PhoenixSectionHeader("Allow Rules", c.green) }
                    items(allow, key = { it.id }) { rule ->
                        RuleItem(rule,
                            onDelete = { scope.launch { dao.deleteRule(rule) } },
                            onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
                    }
                }
                if (block.isNotEmpty()) {
                    item { PhoenixSectionHeader("Block Rules", c.red) }
                    items(block, key = { it.id }) { rule ->
                        RuleItem(rule,
                            onDelete = { scope.launch { dao.deleteRule(rule) } },
                            onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
                    }
                }
            }
        }
    }
}

// ── Add rule dialog ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRuleDialog(
    title: String,
    initialAction: RuleAction = RuleAction.BLOCK,
    initialPattern: String = "",
    onDismiss: () -> Unit,
    onAdd: (FilterRule) -> Unit
) {
    val c = LocalAppColors.current

    var pattern      by remember { mutableStateOf(initialPattern) }
    var matchType    by remember { mutableStateOf(MatchType.SUBDOMAIN) }
    var action       by remember { mutableStateOf(initialAction) }
    var scope        by remember { mutableStateOf(RuleScope.ANY) }
    var menuExpanded by remember { mutableStateOf(false) }
    val trimmed = pattern.trim().lowercase()

    LaunchedEffect(initialPattern) {
        if (initialPattern.isNotBlank()) pattern = initialPattern
    }

    val wildcardExamples = remember(trimmed, matchType, scope) {
        buildWildcardExamples(trimmed, matchType, scope)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.surface,
        tonalElevation   = 0.dp,
        shape            = PhoenixShapeLarge,
        title = { Text(title, fontWeight = FontWeight.Bold, color = PhoenixFlame) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // BLOCK / ALLOW toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RuleAction.BLOCK to c.red, RuleAction.ALLOW to c.green)
                        .forEach { (a, color) ->
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

                // Scope selector
                Column {
                    Text("Scope", fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(RuleScope.ANY to "Any", RuleScope.DNS to "DNS", RuleScope.URL to "URL")
                            .forEach { (s, label) ->
                                val selected = scope == s
                                Box(
                                    Modifier.weight(1f)
                                        .background(if (selected) PhoenixFlame.copy(0.12f) else c.card, PhoenixShapeSmall)
                                        .border(1.dp, if (selected) PhoenixFlame.copy(0.6f) else c.borderMid, PhoenixShapeSmall)
                                        .clickable { scope = s }.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                                        color = if (selected) PhoenixFlame else c.textSecondary)
                                }
                            }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (scope) {
                            RuleScope.ANY -> "Match against domain name and full URL"
                            RuleScope.DNS -> "Match against DNS domain name only"
                            RuleScope.URL -> "Match against full URL or SNI hostname"
                        },
                        fontSize = 10.sp, color = c.textSecondary.copy(0.6f))
                }

                // Pattern input
                OutlinedTextField(
                    value         = pattern,
                    onValueChange = { pattern = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    label         = { Text("Pattern", fontSize = 12.sp) },
                    placeholder   = {
                        Text(
                            when (scope) {
                                RuleScope.URL -> "example.com/ads/*"
                                else          -> "*.example.com"
                            },
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.textTertiary)
                    },
                    supportingText = {
                        Text("Use * as wildcard — e.g. *.example.com matches all subdomains",
                            fontSize = 10.sp, color = c.textTertiary)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = PhoenixFlame.copy(0.6f),
                        unfocusedBorderColor = c.borderMid,
                        focusedTextColor     = c.textPrimary,
                        unfocusedTextColor   = c.textPrimary,
                        cursorColor          = PhoenixFlame,
                        focusedLabelColor    = PhoenixFlame,
                        unfocusedLabelColor  = c.textSecondary
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )

                // Wildcard preview
                if (wildcardExamples.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors   = CardDefaults.cardColors(containerColor = PhoenixFlame.copy(alpha = 0.06f)),
                        shape    = PhoenixShapeSmall
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Will match:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = PhoenixFlame, modifier = Modifier.padding(bottom = 6.dp))
                            wildcardExamples.forEach { (ex, matches) ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 1.dp)) {
                                    Icon(
                                        if (matches) Icons.Default.Check else Icons.Default.Close, null,
                                        tint = if (matches) c.green else c.red,
                                        modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(ex,
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                        color = if (matches) c.green.copy(0.9f) else c.red.copy(0.7f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // Match type dropdown
                ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                    OutlinedTextField(
                        value         = "${matchType.displayName()} — ${matchType.description()}",
                        onValueChange = {},
                        readOnly      = true,
                        modifier      = Modifier.menuAnchor().fillMaxWidth(),
                        label         = { Text("Match Type", fontSize = 12.sp) },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = PhoenixFlame.copy(0.6f),
                            unfocusedBorderColor = c.borderMid,
                            focusedTextColor     = c.textPrimary,
                            unfocusedTextColor   = c.textPrimary,
                            focusedLabelColor    = PhoenixFlame,
                            unfocusedLabelColor  = c.textSecondary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                    ExposedDropdownMenu(
                        expanded         = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier         = Modifier.background(c.card)
                    ) {
                        MatchType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName(),
                                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PhoenixFlame)
                                        Text(type.description(), fontSize = 11.sp, color = c.textSecondary)
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
                    onAdd(FilterRule(packageName = null, pattern = trimmed,
                        matchType = matchType, action = action, scope = scope))
                    onDismiss()
                })
        },
        dismissButton = { PhoenixButton("Cancel", c.textSecondary, onClick = onDismiss) }
    )
}

// ── Wildcard example builder ──────────────────────────────────────────────────

private fun buildWildcardExamples(
    pattern: String, matchType: MatchType, scope: RuleScope
): List<Pair<String, Boolean>> {
    if (pattern.isBlank()) return emptyList()

    val lower = pattern.lowercase()
    val hasWildcard = lower.contains("*")
    val examples = mutableListOf<Pair<String, Boolean>>()

    val testDomains = listOf(
        lower.replace("*", "cdn"),
        lower.replace("*", "api"),
        lower.replace("*.", "www."),
        "evil${lower.replaceFirstChar { it.uppercase() }.replace("*", "x")}.com",
        "unrelated.com"
    ).filter { it.isNotBlank() && !it.startsWith(".") && it.contains(".") }.distinct().take(5)

    for (domain in testDomains) {
        val matches = if (hasWildcard) matchWildcard(domain, lower)
        else when (matchType) {
            MatchType.EXACT     -> domain == lower
            MatchType.SUBDOMAIN -> domain == lower || domain.endsWith(".$lower")
            MatchType.CONTAINS  -> domain.contains(lower)
            MatchType.PREFIX    -> domain.startsWith(lower)
            MatchType.SUFFIX    -> domain.endsWith(lower)
            MatchType.WILDCARD  -> matchWildcard(domain, lower)
        }
        if (domain.isNotBlank()) examples.add(domain to matches)
    }

    if (scope == RuleScope.URL || scope == RuleScope.ANY) {
        val domainPart = lower.substringBefore("/").replace("*.", "").ifBlank { "example.com" }
        val urlExamples = listOf(
            "https://$domainPart/path",
            "https://$domainPart/ads/banner.js",
            "https://cdn.${domainPart}/resource"
        ).filter { it.isNotBlank() }.take(3)

        for (url in urlExamples) {
            val matches = if (hasWildcard) matchWildcard(url, lower)
            else when (matchType) {
                MatchType.EXACT     -> url == lower
                MatchType.SUBDOMAIN -> url.contains(lower, ignoreCase = true)
                MatchType.CONTAINS  -> url.contains(lower)
                MatchType.PREFIX    -> url.startsWith(lower)
                MatchType.SUFFIX    -> url.endsWith(lower)
                MatchType.WILDCARD  -> matchWildcard(url, lower)
            }
            if (url.isNotBlank()) examples.add(url to matches)
        }
    }

    return examples.take(7)
}

// ── Alert dialog ──────────────────────────────────────────────────────────────

@Composable
fun PhoenixAlertDialog(
    title: String, text: String, confirmText: String, confirmColor: Color,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = c.surface,
        tonalElevation   = 0.dp,
        shape            = PhoenixShapeLarge,
        title = { Text(title, fontWeight = FontWeight.Bold, color = confirmColor) },
        text  = { Text(text, fontSize = 13.sp, color = c.textSecondary) },
        confirmButton = { PhoenixButton(confirmText, confirmColor, onClick = onConfirm) },
        dismissButton = { PhoenixButton("Cancel", c.textSecondary, onClick = onDismiss) }
    )
}

// ── Primitive components ──────────────────────────────────────────────────────

@Composable
fun PhoenixButton(
    text: String, color: Color,
    onClick: () -> Unit, enabled: Boolean = true
) {
    val c = LocalAppColors.current
    val bgAlpha = if (c.isDark) { if (enabled) 0.12f else 0.05f } else { if (enabled) 0.18f else 0.08f }
    Box(
        Modifier.alpha(if (enabled) 1f else 0.4f)
            .background(color.copy(bgAlpha), PhoenixShapeSmall)
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
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = color)
    }
}

@Composable
fun PhoenixFab(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    contentDescription: String
) {
    val c = LocalAppColors.current
    val bgAlpha = if (c.isDark) 0.12f else 0.18f
    Box(
        Modifier.size(52.dp).background(color.copy(bgAlpha), PhoenixShapeMedium)
            .border(1.dp, color.copy(0.65f), PhoenixShapeMedium)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PhoenixEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector, message: String
) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(52.dp), tint = PhoenixFlame.copy(0.15f))
        Spacer(Modifier.height(20.dp))
        Text(message, textAlign = TextAlign.Center, fontSize = 13.sp,
            lineHeight = 20.sp, color = LocalAppColors.current.textSecondary.copy(0.7f))
    }
}