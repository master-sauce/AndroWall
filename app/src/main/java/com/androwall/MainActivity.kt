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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.androwall.data.*
import com.androwall.ui.theme.AndroWallTheme
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

    // Load persisted filter mode into the companion StateFlow once at startup
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

    // Live filter mode from companion StateFlow
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
            label.contains(searchQuery, ignoreCase = true) || app.packageName.contains(searchQuery, ignoreCase = true)
        }
        base.sortedWith(
            compareByDescending<ApplicationInfo> { it.packageName in enabledPackages }
                .thenBy { pm.getApplicationLabel(it).toString() }
        )
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear History") },
            text = { Text("All connection logs will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = { OutlinedButton(onClick = { showClearLogsDialog = false }) { Text("Cancel") } }
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
        topBar = {
            LargeTopAppBar(
                title = { Text("AndroWall", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    label = { Text("Apps") }, icon = { Icon(Icons.Default.Home, null) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    label = { Text("History") }, icon = { Icon(Icons.Default.List, null) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    label = {
                        BadgedBox(badge = {
                            if (globalRules.isNotEmpty()) Badge { Text("${globalRules.size}") }
                        }) { Text("Rules") }
                    },
                    icon = { Icon(Icons.Default.Lock, null) }
                )
            }
        },
        floatingActionButton = {
            when (selectedTab) {
                1 -> AnimatedVisibility(
                    visible = recentLogs.isNotEmpty(),
                    enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = { showClearLogsDialog = true },
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) { Icon(Icons.Default.Delete, "Clear logs", tint = MaterialTheme.colorScheme.onErrorContainer) }
                }
                2 -> FloatingActionButton(onClick = { showAddGlobalRuleDialog = true }) {
                    Icon(Icons.Default.Add, "Add rule")
                }
                else -> {}
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                    OutlinedTextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search apps…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty())
                                IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, "Clear") }
                        },
                        shape = RoundedCornerShape(12.dp), singleLine = true
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        if (filteredApps.isEmpty()) {
                            item { EmptyState(Icons.Default.Search, "No apps match \"$searchQuery\".") }
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

// ── Filter mode card ──────────────────────────────────────────────────────────

@Composable
fun FilterModeCard(currentMode: FilterMode, onModeChange: (FilterMode) -> Unit) {
    val blacklistSelected = currentMode == FilterMode.BLACKLIST
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Filter Mode", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Blacklist
                OutlinedButton(
                    onClick = { onModeChange(FilterMode.BLACKLIST) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (blacklistSelected) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Blacklist", fontWeight = FontWeight.Bold,
                            color = if (blacklistSelected) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurface)
                        Text("Block matched, allow rest", fontSize = 10.sp,
                            color = if (blacklistSelected) MaterialTheme.colorScheme.onErrorContainer.copy(0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
                // Whitelist
                OutlinedButton(
                    onClick = { onModeChange(FilterMode.WHITELIST) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (!blacklistSelected) Color(0xFF1B5E20).copy(alpha = 0.2f) else Color.Transparent
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Whitelist", fontWeight = FontWeight.Bold,
                            color = if (!blacklistSelected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface)
                        Text("Allow matched, block rest", fontSize = 10.sp,
                            color = if (!blacklistSelected) Color(0xFF4CAF50).copy(0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (blacklistSelected)
                    "All DNS is allowed. Add BLOCK rules to deny specific domains."
                else
                    "All DNS is blocked. Add ALLOW rules to permit specific domains.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        FilterModeCard(
            currentMode = filterMode,
            onModeChange = { VpnTrackerService.setFilterMode(context, it) }
        )
        if (rules.isEmpty()) {
            EmptyState(
                Icons.Default.Lock,
                if (filterMode == FilterMode.BLACKLIST)
                    "No global rules yet.\nTap + to add BLOCK rules.\nMatched domains will be denied for all enabled apps."
                else
                    "No global rules yet.\nTap + to add ALLOW rules.\nOnly matched domains will be permitted for all enabled apps."
            )
        } else {
            GlobalRulesList(rules, dao, scope)
        }
    }
}

@Composable
fun GlobalRulesList(rules: List<FilterRule>, dao: AppDao, scope: CoroutineScope) {
    val allowRules = rules.filter { it.action == RuleAction.ALLOW }
    val blockRules  = rules.filter { it.action == RuleAction.BLOCK }
    LazyColumn {
        if (allowRules.isNotEmpty()) {
            item {
                Text("ALLOW — Whitelist",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), fontSize = 12.sp)
            }
            items(allowRules, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
            }
        }
        if (blockRules.isNotEmpty()) {
            item {
                Text("BLOCK — Blacklist",
                    Modifier.padding(start = 16.dp, end = 16.dp,
                        top = if (allowRules.isNotEmpty()) 16.dp else 12.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            items(blockRules, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
            }
        }
    }
}

// ── Firewall status card ──────────────────────────────────────────────────────

@Composable
fun FirewallStatusCard(onStart: () -> Unit, onStop: () -> Unit) {
    val isRunning by VpnTrackerService.isRunning.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(
                    if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape
                ), contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isRunning) Icons.Default.Lock else Icons.Default.LockOpen, null,
                    tint = if (isRunning) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Firewall Engine", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    if (isRunning) "Active & Protecting" else "Engine Stopped",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 14.sp
                )
                if (!isRunning)
                    Text("Enable filtering on at least one app first.",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f), fontSize = 11.sp)
            }
            if (isRunning) {
                Button(onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Stop") }
            } else {
                Button(onClick = onStart, shape = RoundedCornerShape(12.dp)) { Text("Start") }
            }
        }
    }
}

// ── App list item ─────────────────────────────────────────────────────────────

@Composable
fun AppListItem(app: ApplicationInfo, config: AppConfig?, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(app.packageName) { context.packageManager.getApplicationLabel(app).toString() }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        headlineContent = { Text(label, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(app.packageName, fontSize = 12.sp) },
        leadingContent = { AppIconImage(app.packageName, Modifier.size(40.dp)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (config?.isFilteringEnabled == true) {
                    Icon(Icons.Default.CheckCircle, "Active", tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
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
            title = "Add Rule for $label",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss = { showAddRuleDialog = false },
            onAdd = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = { AppIconImage(packageName, Modifier.size(32.dp)) }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = selectedSection == 1, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                FloatingActionButton(onClick = { showAddRuleDialog = true }) {
                    Icon(Icons.Default.Add, "Add rule")
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable Firewall", fontWeight = FontWeight.Bold)
                        Text(
                            if (appConfig?.isFilteringEnabled == true) "DNS filtering active" else "App bypasses firewall",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = appConfig?.isFilteringEnabled ?: false,
                        onCheckedChange = { enabled ->
                            scope.launch { dao.insertAppConfig(AppConfig(packageName, label, enabled)) }
                        }
                    )
                }
            }

            TabRow(selectedTabIndex = selectedSection) {
                Tab(selected = selectedSection == 0, onClick = { selectedSection = 0 }, text = { Text("DNS Activity") })
                Tab(
                    selected = selectedSection == 1, onClick = { selectedSection = 1 },
                    text = {
                        BadgedBox(badge = {
                            if (appRules.isNotEmpty()) Badge { Text("${appRules.size}") }
                        }) { Text("App Rules") }
                    }
                )
            }

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
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("$globalCount global rule${if (globalCount != 1) "s" else ""} also apply here. Manage them in the Rules tab.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (rules.isEmpty()) {
            EmptyState(Icons.Default.Lock, "No app-specific rules for $appLabel.\nTap + to add, or tap BLOCK/ALLOW in DNS Activity.")
        } else {
            val allowRules = rules.filter { it.action == RuleAction.ALLOW }
            val blockRules = rules.filter { it.action == RuleAction.BLOCK }
            LazyColumn(Modifier.weight(1f)) {
                if (allowRules.isNotEmpty()) {
                    item { Text("ALLOW", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), fontSize = 12.sp) }
                    items(allowRules, key = { it.id }) { rule ->
                        RuleItem(rule, onDelete = { scope.launch { dao.deleteRule(rule) } }, onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
                    }
                }
                if (blockRules.isNotEmpty()) {
                    item { Text("BLOCK", Modifier.padding(start = 16.dp, end = 16.dp, top = if (allowRules.isNotEmpty()) 16.dp else 12.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    items(blockRules, key = { it.id }) { rule ->
                        RuleItem(rule, onDelete = { scope.launch { dao.deleteRule(rule) } }, onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
                    }
                }
            }
        }
    }
}

// ── Rule item ─────────────────────────────────────────────────────────────────

@Composable
fun RuleItem(rule: FilterRule, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    val isBlock = rule.action == RuleAction.BLOCK
    val color = if (isBlock) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
    ListItem(
        modifier = Modifier.alpha(if (rule.isEnabled) 1f else 0.45f),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                    Text(rule.matchType.displayName().uppercase(),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = color, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(rule.pattern, fontWeight = FontWeight.Medium)
            }
        },
        supportingContent = {
            Text("${if (isBlock) "Block" else "Allow"} · ${rule.matchType.description()}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = rule.isEnabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
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
        EmptyState(Icons.Default.List, "No DNS history yet.\nStart the firewall to begin monitoring.")
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = remember(log.isBlocked, log.domain, globalRules, filterMode) {
                log.isBlocked || isEffectivelyBlocked(log.domain, globalRules, filterMode)
            }
            LogItemExtended(
                log = log,
                isEffectivelyBlocked = blocked,
                filterMode = filterMode,
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
        EmptyState(Icons.Default.Info, "No DNS activity yet.\nMake sure the firewall is running and this app is enabled.")
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = remember(log.isBlocked, log.domain, allRules, filterMode) {
                log.isBlocked || isEffectivelyBlocked(log.domain, allRules, filterMode)
            }
            LogItemExtended(
                log = log,
                isEffectivelyBlocked = blocked,
                filterMode = filterMode,
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
    ListItem(
        headlineContent = { Text(log.domain, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                remember(log.timestamp) { logDateFormat.format(Date(log.timestamp)) },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        },
        trailingContent = {
            when {
                // Domain is blocked
                isEffectivelyBlocked && filterMode == FilterMode.WHITELIST -> {
                    // In whitelist mode: primary action is to ALLOW it
                    TextButton(onClick = onAllow) {
                        Text("ALLOW", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                }
                isEffectivelyBlocked -> {
                    // In blacklist mode: it's blocked, show badge
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                        Text("BLOCKED", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
                // Domain is allowed
                filterMode == FilterMode.WHITELIST -> {
                    // In whitelist mode: explicitly allowed, show badge
                    Surface(color = Color(0xFF1B5E20).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                        Text("ALLOWED", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
                else -> {
                    // In blacklist mode: allowed by default, offer to block
                    TextButton(onClick = onBlock) { Text("BLOCK") }
                }
            }
        }
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

// ── Add rule dialog ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRuleDialog(
    title: String,
    initialAction: RuleAction = RuleAction.BLOCK,
    onDismiss: () -> Unit,
    onAdd: (FilterRule) -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf(MatchType.SUBDOMAIN) }
    var action by remember { mutableStateOf(initialAction) }
    var menuExpanded by remember { mutableStateOf(false) }
    val trimmed = pattern.trim().lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { action = RuleAction.BLOCK }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (action == RuleAction.BLOCK) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                        )
                    ) {
                        Text("Block",
                            color = if (action == RuleAction.BLOCK) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurface)
                    }
                    OutlinedButton(
                        onClick = { action = RuleAction.ALLOW }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (action == RuleAction.ALLOW) Color(0xFF1B5E20).copy(0.2f) else Color.Transparent
                        )
                    ) {
                        Text("Allow",
                            color = if (action == RuleAction.ALLOW) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface)
                    }
                }
                OutlinedTextField(
                    value = pattern, onValueChange = { pattern = it },
                    label = { Text("Pattern") },
                    placeholder = { Text("e.g. example.com  or  ads  or  .ru") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                    OutlinedTextField(
                        value = "${matchType.displayName()} — ${matchType.description()}",
                        onValueChange = {}, readOnly = true, label = { Text("Match Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        MatchType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName(), fontWeight = FontWeight.Medium)
                                        Text(type.description(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { matchType = type; menuExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(FilterRule(packageName = null, pattern = trimmed, matchType = matchType, action = action)); onDismiss() },
                enabled = trimmed.isNotBlank(),
                colors = if (action == RuleAction.BLOCK)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) { Text(if (action == RuleAction.BLOCK) "Block" else "Allow") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Spacer(Modifier.height(20.dp))
        Text(message, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 14.sp, lineHeight = 20.sp)
    }
}