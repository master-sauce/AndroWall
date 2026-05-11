package com.androwall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.shape.CutCornerShape
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
        enableEdgeToEdge()
        setContent { AndroWallTheme { AndroWallApp() } }
    }
}

@Composable
fun AndroWallApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val dao = AppDatabase.getDatabase(context).appDao()

    LaunchedEffect(Unit) { VpnTrackerService.loadPersistedMode(context) }

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

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, dao: AppDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val appConfigs by dao.getAllAppConfigs().collectAsState(initial = emptyList())
    val recentLogs by dao.getRecentLogs().collectAsState(initial = emptyList())
    val globalRules by dao.getGlobalRules().collectAsState(initial = emptyList())
    val filterMode by VpnTrackerService.filterMode.collectAsState()

    var installedApps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var showAddGlobalRuleDialog by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK)
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

    if (showClearLogsDialog) {
        CyberAlertDialog(
            title = "CLEAR HISTORY",
            text = "All connection logs will be permanently deleted.",
            confirmText = "PURGE",
            confirmColor = NeonRed,
            onConfirm = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss = { showClearLogsDialog = false }
        )
    }

    if (showAddGlobalRuleDialog) {
        AddRuleDialog(
            title = "ADD GLOBAL RULE",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss = { showAddGlobalRuleDialog = false },
            onAdd = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = null)) } }
        )
    }

    Scaffold(
        containerColor = CyberBlack,
        topBar = {
            // Custom top bar — monospace title with grid background
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CyberDeep)
                    .statusBarsPadding()
            ) {
                CyberGrid(Modifier.matchParentSize(), cellSize = 24.dp)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        "ANDROWALL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize   = 26.sp,
                        letterSpacing = 8.sp,
                        color = NeonCyan
                    )
                    Text(
                        "DNS FIREWALL  //  NETWORK SENTINEL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                        fontSize   = 9.sp,
                        letterSpacing = 2.sp,
                        color = NeonCyanDim
                    )
                }
                // Bottom accent line
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(NeonCyan.copy(0.8f), NeonCyan.copy(0.2f), Color.Transparent)
                            )
                        )
                )
            }
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CyberDeep)
                    .navigationBarsPadding()
            ) {
                // Top accent line on nav bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, NeonCyan.copy(0.4f), Color.Transparent)
                            )
                        )
                )
                NavigationBar(
                    containerColor = Color.Transparent,
                    contentColor = NeonCyan,
                    tonalElevation = 0.dp
                ) {
                    listOf(
                        Triple(0, Icons.Default.Home, "APPS"),
                        Triple(1, Icons.Default.List, "HISTORY"),
                        Triple(2, Icons.Default.Lock, "RULES")
                    ).forEach { (idx, icon, label) ->
                        NavigationBarItem(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            icon = { Icon(icon, null, Modifier.size(20.dp)) },
                            label = {
                                Text(label,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = NeonCyan,
                                selectedTextColor   = NeonCyan,
                                unselectedIconColor = CyberTextSecondary,
                                unselectedTextColor = CyberTextSecondary,
                                indicatorColor      = NeonCyanGhost
                            )
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (selectedTab) {
                1 -> AnimatedVisibility(
                    visible = recentLogs.isNotEmpty(),
                    enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()
                ) {
                    CyberFab(
                        onClick = { showClearLogsDialog = true },
                        icon = Icons.Default.Delete,
                        color = NeonRed,
                        contentDescription = "Clear logs"
                    )
                }
                2 -> CyberFab(
                    onClick = { showAddGlobalRuleDialog = true },
                    icon = Icons.Default.Add,
                    color = NeonCyan,
                    contentDescription = "Add rule"
                )
                else -> {}
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(CyberBlack)
        ) {
            FirewallStatusCard(
                onStart = {
                    val intent = VpnService.prepare(context)
                    if (intent != null) vpnLauncher.launch(intent)
                    else context.startService(Intent(context, VpnTrackerService::class.java))
                },
                onStop = {
                    context.startService(
                        Intent(context, VpnTrackerService::class.java).apply { action = "STOP" }
                    )
                }
            )
            when (selectedTab) {
                0 -> {
                    // Cyber search field
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(CyberNavy, CyberShapeMedium)
                            .border(1.dp, CyberBorderBright.copy(0.5f), CyberShapeMedium)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("SEARCH APPS...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp,
                                    color = CyberTextSecondary)
                            },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = NeonCyan, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty())
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, "Clear", tint = NeonCyanDim, modifier = Modifier.size(16.dp))
                                    }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor     = CyberTextPrimary,
                                unfocusedTextColor   = CyberTextPrimary,
                                cursorColor          = NeonCyan
                            ),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 13.sp
                            )
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        if (filteredApps.isEmpty()) {
                            item { CyberEmptyState(Icons.Default.Search, "NO MATCHING APPS FOUND") }
                        } else {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val config = appConfigs.find { it.packageName == app.packageName }
                                AppListItem(app, config) { navController.navigate("app_detail/${app.packageName}") }
                            }
                        }
                    }
                }
                1 -> GlobalLogList(recentLogs, globalRules, filterMode, dao, scope)
                2 -> GlobalRulesTab(globalRules, filterMode, context, dao, scope)
            }
        }
    }
}

// ── Firewall status card ──────────────────────────────────────────────────────

@Composable
fun FirewallStatusCard(onStart: () -> Unit, onStop: () -> Unit) {
    val isRunning by VpnTrackerService.isRunning.collectAsState()
    val borderColor = if (isRunning) NeonGreen else CyberBorderMid
    val accentColor = if (isRunning) NeonGreen else NeonCyanDim

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(CyberNavy, CyberShapeLarge)
            .border(1.dp, borderColor.copy(0.5f), CyberShapeLarge)
            .clip(CyberShapeLarge)
    ) {
        // Grid overlay
        CyberGrid(Modifier.matchParentSize(), color = accentColor, cellSize = 32.dp)
        // Scan line only when running
        if (isRunning) ScanLineOverlay(Modifier.matchParentSize(), color = NeonGreen)

        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingDot(
                    color = if (isRunning) NeonGreen else CyberTextSecondary,
                    size  = 14.dp
                )
                Spacer(Modifier.height(6.dp))
                Icon(
                    if (isRunning) Icons.Default.Lock else Icons.Default.LockOpen,
                    null,
                    tint = if (isRunning) NeonGreen else CyberTextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isRunning) "SYS:ACTIVE" else "SYS:OFFLINE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize   = 16.sp,
                    letterSpacing = 2.sp,
                    color = if (isRunning) NeonGreen else CyberTextSecondary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isRunning) "DNS INTERCEPT  //  FILTERING ACTIVE"
                    else           "ENGINE OFFLINE  //  UNPROTECTED",
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 9.sp,
                    letterSpacing = 1.sp,
                    color = if (isRunning) NeonGreen.copy(0.6f) else CyberTextTertiary
                )
                if (!isRunning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "// enable filtering on at least one app",
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 9.sp,
                        letterSpacing = 0.5.sp,
                        color = NeonRed.copy(0.7f)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isRunning) {
                CyberButton(text = "STOP", color = NeonRed, onClick = onStop)
            } else {
                CyberButton(text = "START", color = NeonGreen, onClick = onStart)
            }
        }
    }
}

// ── App list item ─────────────────────────────────────────────────────────────

@Composable
fun AppListItem(app: ApplicationInfo, config: AppConfig?, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(app.packageName) { context.packageManager.getApplicationLabel(app).toString() }
    val isEnabled = config?.isFilteringEnabled == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent stripe
        Box(
            Modifier
                .width(3.dp)
                .height(52.dp)
                .background(
                    Brush.verticalGradient(
                        if (isEnabled) listOf(NeonGreen, NeonCyan)
                        else listOf(CyberBorderMid, CyberBorderFaint)
                    ),
                    CutCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        // App icon
        Box(
            Modifier
                .size(38.dp)
                .background(CyberSlate, CyberShapeSmall)
                .border(0.5.dp, if (isEnabled) NeonCyan.copy(0.3f) else CyberBorderMid, CyberShapeSmall)
                .clip(CyberShapeSmall)
        ) {
            AppIconImage(app.packageName, Modifier.fillMaxSize().padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                color = CyberTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                fontFamily = FontFamily.Monospace,
                fontSize   = 9.sp,
                letterSpacing = 0.3.sp,
                color = CyberTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isEnabled) {
            NeonChip("ACTIVE", NeonGreen)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = CyberBorderBright, modifier = Modifier.size(16.dp))
    }

    // Divider
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 31.dp)
            .height(0.5.dp)
            .background(CyberBorderFaint)
    )
}

@Composable
fun AppIconImage(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            try { view.setImageDrawable(context.packageManager.getApplicationIcon(packageName)) }
            catch (_: Exception) { view.setImageResource(android.R.drawable.sym_def_app_icon) }
        },
        modifier = modifier
    )
}

// ── Filter mode card ──────────────────────────────────────────────────────────

@Composable
fun FilterModeCard(currentMode: FilterMode, onModeChange: (FilterMode) -> Unit) {
    val isBlacklist = currentMode == FilterMode.BLACKLIST
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(CyberNavy, CyberShapeMedium)
            .border(1.dp, NeonCyan.copy(0.3f), CyberShapeMedium)
            .padding(16.dp)
    ) {
        Column {
            Text("FILTER MODE",
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 10.sp, letterSpacing = 2.sp, color = NeonCyan)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Blacklist
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (isBlacklist) NeonRedGhost else CyberSlate,
                            CyberShapeSmall
                        )
                        .border(
                            1.dp,
                            if (isBlacklist) NeonRed.copy(0.7f) else CyberBorderMid,
                            CyberShapeSmall
                        )
                        .clickable { onModeChange(FilterMode.BLACKLIST) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text("BLACKLIST",
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, letterSpacing = 1.sp,
                            color = if (isBlacklist) NeonRed else CyberTextSecondary)
                        Text("block matched / allow rest",
                            fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            color = if (isBlacklist) NeonRed.copy(0.6f) else CyberTextTertiary)
                    }
                }
                // Whitelist
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (!isBlacklist) NeonGreenGhost else CyberSlate,
                            CyberShapeSmall
                        )
                        .border(
                            1.dp,
                            if (!isBlacklist) NeonGreen.copy(0.7f) else CyberBorderMid,
                            CyberShapeSmall
                        )
                        .clickable { onModeChange(FilterMode.WHITELIST) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text("WHITELIST",
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, letterSpacing = 1.sp,
                            color = if (!isBlacklist) NeonGreen else CyberTextSecondary)
                        Text("allow matched / block rest",
                            fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            color = if (!isBlacklist) NeonGreen.copy(0.6f) else CyberTextTertiary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "// ${if (isBlacklist) "all traffic permitted unless matched by a BLOCK rule"
                else "all traffic denied unless matched by an ALLOW rule"}",
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.5.sp,
                color = NeonCyanDim
            )
        }
    }
}

// ── Global rules tab ──────────────────────────────────────────────────────────

@Composable
fun GlobalRulesTab(
    rules: List<FilterRule>,
    filterMode: FilterMode,
    context: android.content.Context,
    dao: AppDao,
    scope: CoroutineScope
) {
    Column(Modifier.fillMaxSize()) {
        FilterModeCard(filterMode) { VpnTrackerService.setFilterMode(context, it) }
        if (rules.isEmpty()) {
            CyberEmptyState(
                Icons.Default.Lock,
                if (filterMode == FilterMode.BLACKLIST)
                    "NO BLOCK RULES DEFINED\n// all traffic is permitted\n// tap + to add rules"
                else
                    "NO ALLOW RULES DEFINED\n// all traffic is blocked\n// tap + to add rules"
            )
        } else {
            RulesListContent(rules, dao, scope)
        }
    }
}

@Composable
fun RulesListContent(rules: List<FilterRule>, dao: AppDao, scope: CoroutineScope) {
    val allowRules = rules.filter { it.action == RuleAction.ALLOW }
    val blockRules  = rules.filter { it.action == RuleAction.BLOCK }
    LazyColumn {
        if (allowRules.isNotEmpty()) {
            item { CyberSectionHeader("// ALLOW — WHITELIST", NeonGreen) }
            items(allowRules, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
            }
        }
        if (blockRules.isNotEmpty()) {
            item { CyberSectionHeader("// BLOCK — BLACKLIST", NeonRed) }
            items(blockRules, key = { it.id }) { rule ->
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
    dao: AppDao,
    scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        CyberEmptyState(Icons.Default.List, "NO DNS HISTORY\n// start the firewall to begin monitoring")
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = remember(log.isBlocked, log.domain, globalRules, filterMode) {
                log.isBlocked || isEffectivelyBlocked(log.domain, globalRules, filterMode)
            }
            LogItemExtended(
                log = log, isEffectivelyBlocked = blocked, filterMode = filterMode,
                onBlock = {
                    scope.launch {
                        dao.insertRule(FilterRule(packageName = null, pattern = log.domain,
                            matchType = MatchType.SUBDOMAIN, action = RuleAction.BLOCK))
                    }
                },
                onAllow = {
                    scope.launch {
                        dao.insertRule(FilterRule(packageName = null, pattern = log.domain,
                            matchType = MatchType.SUBDOMAIN, action = RuleAction.ALLOW))
                    }
                }
            )
        }
    }
}

@Composable
fun AppTrafficList(
    logs: List<ConnectionLog>,
    allRules: List<FilterRule>,
    filterMode: FilterMode,
    packageName: String,
    dao: AppDao,
    scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        CyberEmptyState(Icons.Default.Info,
            "NO DNS ACTIVITY\n// enable firewall for this app\n// then start the engine")
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = remember(log.isBlocked, log.domain, allRules, filterMode) {
                log.isBlocked || isEffectivelyBlocked(log.domain, allRules, filterMode)
            }
            LogItemExtended(
                log = log, isEffectivelyBlocked = blocked, filterMode = filterMode,
                onBlock = {
                    scope.launch {
                        dao.insertRule(FilterRule(packageName = packageName, pattern = log.domain,
                            matchType = MatchType.SUBDOMAIN, action = RuleAction.BLOCK))
                    }
                },
                onAllow = {
                    scope.launch {
                        dao.insertRule(FilterRule(packageName = packageName, pattern = log.domain,
                            matchType = MatchType.SUBDOMAIN, action = RuleAction.ALLOW))
                    }
                }
            )
        }
    }
}

// ── Log item ──────────────────────────────────────────────────────────────────

private val logDateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

@Composable
fun LogItemExtended(
    log: ConnectionLog,
    isEffectivelyBlocked: Boolean,
    filterMode: FilterMode,
    onBlock: () -> Unit,
    onAllow: () -> Unit
) {
    val accentColor = when {
        isEffectivelyBlocked -> NeonRed
        filterMode == FilterMode.WHITELIST -> NeonGreen
        else -> CyberBorderMid
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(44.dp)
                .background(accentColor.copy(if (isEffectivelyBlocked || filterMode == FilterMode.WHITELIST) 0.8f else 0.2f),
                    CutCornerShape(1.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                log.domain,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize   = 12.sp,
                color = CyberTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                remember(log.timestamp) { logDateFormat.format(Date(log.timestamp)) },
                fontFamily = FontFamily.Monospace,
                fontSize   = 9.sp,
                letterSpacing = 0.5.sp,
                color = CyberTextSecondary
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            isEffectivelyBlocked && filterMode == FilterMode.WHITELIST ->
                CyberTextButton("ALLOW", NeonGreen, onAllow)
            isEffectivelyBlocked ->
                NeonChip("BLOCKED", NeonRed)
            filterMode == FilterMode.WHITELIST ->
                NeonChip("ALLOWED", NeonGreen)
            else ->
                CyberTextButton("BLOCK", NeonRed, onBlock)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 30.dp)
            .height(0.5.dp)
            .background(CyberBorderFaint)
    )
}

// ── Rule item ─────────────────────────────────────────────────────────────────

@Composable
fun RuleItem(rule: FilterRule, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    val isBlock = rule.action == RuleAction.BLOCK
    val color = if (isBlock) NeonRed else NeonGreen

    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (rule.isEnabled) 1f else 0.35f)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(50.dp)
                .background(color.copy(if (rule.isEnabled) 0.8f else 0.3f), CutCornerShape(1.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonChip(rule.matchType.displayName().uppercase(), color)
                Spacer(Modifier.width(8.dp))
                Text(
                    rule.pattern,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp,
                    color = CyberTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "// ${if (isBlock) "block" else "allow"} · ${rule.matchType.description()}",
                fontFamily = FontFamily.Monospace,
                fontSize   = 9.sp,
                letterSpacing = 0.3.sp,
                color = CyberTextSecondary
            )
        }
        Switch(
            checked = rule.isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor  = CyberVoid,
                checkedTrackColor  = color.copy(0.8f),
                uncheckedThumbColor = CyberTextTertiary,
                uncheckedTrackColor = CyberSlate
            )
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete", tint = NeonRed.copy(0.6f), modifier = Modifier.size(16.dp))
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 30.dp).height(0.5.dp).background(CyberBorderFaint))
}

// ── App detail screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(navController: NavController, dao: AppDao, packageName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = context.packageManager

    val label = remember(packageName) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
        catch (_: Exception) { packageName }
    }

    val configs by dao.getAllAppConfigs().collectAsState(initial = emptyList())
    val appConfig = configs.find { it.packageName == packageName }
    val appRules by dao.getAppRules(packageName).collectAsState(initial = emptyList())
    val globalRules by dao.getGlobalRules().collectAsState(initial = emptyList())
    val allLogs by dao.getRecentLogs().collectAsState(initial = emptyList())
    val filterMode by VpnTrackerService.filterMode.collectAsState()
    val combinedRules = remember(appRules, globalRules) { appRules + globalRules }

    var selectedSection by remember { mutableIntStateOf(0) }
    var showAddRuleDialog by remember { mutableStateOf(false) }

    if (showAddRuleDialog) {
        AddRuleDialog(
            title = "ADD RULE // $label",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss = { showAddRuleDialog = false },
            onAdd = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
        )
    }

    Scaffold(
        containerColor = CyberBlack,
        topBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CyberDeep)
                    .statusBarsPadding()
            ) {
                CyberGrid(Modifier.matchParentSize(), cellSize = 24.dp)
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = NeonCyan)
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(CyberSlate, CyberShapeSmall)
                            .border(0.5.dp, NeonCyan.copy(0.3f), CyberShapeSmall)
                            .clip(CyberShapeSmall)
                    ) {
                        AppIconImage(packageName, Modifier.fillMaxSize().padding(3.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            label.uppercase(),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp,
                            letterSpacing = 2.sp,
                            color = NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            packageName,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 9.sp,
                            color = CyberTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .align(Alignment.BottomStart)
                        .background(Brush.horizontalGradient(listOf(NeonCyan.copy(0.6f), NeonCyan.copy(0.1f), Color.Transparent)))
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = selectedSection == 1, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                CyberFab(onClick = { showAddRuleDialog = true }, icon = Icons.Default.Add,
                    color = NeonCyan, contentDescription = "Add rule")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(CyberBlack)
        ) {
            // Firewall toggle card
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(CyberNavy, CyberShapeMedium)
                    .border(1.dp,
                        if (appConfig?.isFilteringEnabled == true) NeonGreen.copy(0.4f) else CyberBorderMid,
                        CyberShapeMedium)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FIREWALL INTERCEPT",
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, letterSpacing = 1.5.sp, color = NeonCyan)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (appConfig?.isFilteringEnabled == true)
                                "// dns filtering active for this application"
                            else "// application bypasses the firewall engine",
                            fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                            color = if (appConfig?.isFilteringEnabled == true) NeonGreen.copy(0.7f) else CyberTextSecondary
                        )
                    }
                    Switch(
                        checked = appConfig?.isFilteringEnabled ?: false,
                        onCheckedChange = { enabled ->
                            scope.launch { dao.insertAppConfig(AppConfig(packageName, label, enabled)) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = CyberVoid,
                            checkedTrackColor   = NeonGreen.copy(0.8f),
                            uncheckedThumbColor = CyberTextTertiary,
                            uncheckedTrackColor = CyberSlate
                        )
                    )
                }
            }

            // Cyber tab row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(CyberNavy, CyberShapeSmall)
                    .border(1.dp, CyberBorderMid, CyberShapeSmall)
                    .padding(4.dp)
            ) {
                listOf("DNS ACTIVITY", "APP RULES").forEachIndexed { idx, label2 ->
                    val selected = selectedSection == idx
                    val tabLabel = if (idx == 1 && appRules.isNotEmpty()) "$label2 [${appRules.size}]" else label2
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) NeonCyanGhost else Color.Transparent,
                                CyberShapeSmall
                            )
                            .border(
                                if (selected) 1.dp else 0.dp,
                                if (selected) NeonCyan.copy(0.5f) else Color.Transparent,
                                CyberShapeSmall
                            )
                            .clickable { selectedSection = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tabLabel,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 10.sp,
                            letterSpacing = 1.sp,
                            color = if (selected) NeonCyan else CyberTextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (selectedSection == 0) {
                AppTrafficList(allLogs, combinedRules, filterMode, packageName, dao, scope)
            } else {
                AppRulesList(appRules, label, globalRules.size, dao, scope)
            }
        }
    }
}

// ── App rules list ────────────────────────────────────────────────────────────

@Composable
fun AppRulesList(rules: List<FilterRule>, appLabel: String, globalCount: Int, dao: AppDao, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize()) {
        if (globalCount > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(NeonPurpleGhost, CyberShapeSmall)
                    .border(1.dp, NeonPurple.copy(0.3f), CyberShapeSmall)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = NeonPurple)
                Spacer(Modifier.width(8.dp))
                Text(
                    "// $globalCount global rule${if (globalCount != 1) "s" else ""} also apply to this app",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = NeonPurple.copy(0.8f)
                )
            }
        }
        if (rules.isEmpty()) {
            CyberEmptyState(Icons.Default.Lock,
                "NO APP-SPECIFIC RULES\n// tap + to add\n// or tap BLOCK in DNS ACTIVITY")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val allowRules = rules.filter { it.action == RuleAction.ALLOW }
                val blockRules = rules.filter { it.action == RuleAction.BLOCK }
                if (allowRules.isNotEmpty()) {
                    item { CyberSectionHeader("// ALLOW", NeonGreen) }
                    items(allowRules, key = { it.id }) { rule ->
                        RuleItem(rule, onDelete = { scope.launch { dao.deleteRule(rule) } },
                            onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
                    }
                }
                if (blockRules.isNotEmpty()) {
                    item { CyberSectionHeader("// BLOCK", NeonRed) }
                    items(blockRules, key = { it.id }) { rule ->
                        RuleItem(rule, onDelete = { scope.launch { dao.deleteRule(rule) } },
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
fun AddRuleDialog(title: String, initialAction: RuleAction = RuleAction.BLOCK, onDismiss: () -> Unit, onAdd: (FilterRule) -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf(MatchType.SUBDOMAIN) }
    var action by remember { mutableStateOf(initialAction) }
    var menuExpanded by remember { mutableStateOf(false) }
    val trimmed = pattern.trim().lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberDeep,
        tonalElevation = 0.dp,
        shape = CyberShapeLarge,
        title = {
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, color = NeonCyan)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Action toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RuleAction.BLOCK to NeonRed, RuleAction.ALLOW to NeonGreen).forEach { (a, color) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (action == a) color.copy(0.15f) else CyberNavy, CyberShapeSmall)
                                .border(1.dp, if (action == a) color.copy(0.8f) else CyberBorderMid, CyberShapeSmall)
                                .clickable { action = a }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(a.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                fontSize = 11.sp, letterSpacing = 1.5.sp,
                                color = if (action == a) color else CyberTextSecondary)
                        }
                    }
                }
                OutlinedTextField(
                    value = pattern, onValueChange = { pattern = it },
                    label = { Text("PATTERN", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp) },
                    placeholder = { Text("example.com", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = CyberTextTertiary) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = NeonCyan.copy(0.6f),
                        unfocusedBorderColor = CyberBorderMid,
                        focusedTextColor     = CyberTextPrimary,
                        unfocusedTextColor   = CyberTextPrimary,
                        cursorColor          = NeonCyan,
                        focusedLabelColor    = NeonCyan,
                        unfocusedLabelColor  = CyberTextSecondary
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
                ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                    OutlinedTextField(
                        value = "${matchType.displayName()} — ${matchType.description()}",
                        onValueChange = {}, readOnly = true,
                        label = { Text("MATCH TYPE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NeonCyan.copy(0.6f),
                            unfocusedBorderColor = CyberBorderMid,
                            focusedTextColor     = CyberTextPrimary,
                            unfocusedTextColor   = CyberTextPrimary,
                            focusedLabelColor    = NeonCyan,
                            unfocusedLabelColor  = CyberTextSecondary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    )
                    ExposedDropdownMenu(
                        expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(CyberNavy)
                    ) {
                        MatchType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName(), fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NeonCyan)
                                        Text(type.description(), fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp, color = CyberTextSecondary)
                                    }
                                },
                                onClick = { matchType = type; menuExpanded = false },
                                colors = MenuDefaults.itemColors(textColor = CyberTextPrimary)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val confirmColor = if (action == RuleAction.BLOCK) NeonRed else NeonGreen
            CyberButton(text = action.name, color = confirmColor,
                enabled = trimmed.isNotBlank(),
                onClick = {
                    onAdd(FilterRule(packageName = null, pattern = trimmed, matchType = matchType, action = action))
                    onDismiss()
                })
        },
        dismissButton = {
            CyberButton(text = "CANCEL", color = CyberTextSecondary, onClick = onDismiss)
        }
    )
}

// ── Shared dialog ─────────────────────────────────────────────────────────────

@Composable
fun CyberAlertDialog(
    title: String,
    text: String,
    confirmText: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberDeep,
        tonalElevation = 0.dp,
        shape = CyberShapeLarge,
        title = { Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = confirmColor) },
        text  = { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = CyberTextSecondary) },
        confirmButton = { CyberButton(confirmText, confirmColor, onClick = onConfirm) },
        dismissButton = { CyberButton("CANCEL", CyberTextSecondary, onClick = onDismiss) }
    )
}

// ── Primitive components ──────────────────────────────────────────────────────

@Composable
fun CyberButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(color.copy(if (enabled) 0.12f else 0.05f), CyberShapeSmall)
            .border(1.dp, color.copy(if (enabled) 0.7f else 0.2f), CyberShapeSmall)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize   = 11.sp,
            letterSpacing = 1.5.sp,
            color = color.copy(if (enabled) 1f else 0.4f)
        )
    }
}

@Composable
fun CyberTextButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(0.5.dp, color.copy(0.5f), CyberShapeChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 9.sp, letterSpacing = 1.5.sp, color = color)
    }
}

@Composable
fun CyberFab(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(color.copy(0.12f), CyberShapeMedium)
            .border(1.dp, color.copy(0.7f), CyberShapeMedium)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(22.dp))
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
fun CyberEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = NeonCyan.copy(alpha = 0.15f))
        Spacer(Modifier.height(20.dp))
        Text(
            message,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontSize   = 11.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.5.sp,
            color = CyberTextSecondary.copy(0.7f)
        )
    }
}