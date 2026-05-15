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
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#0D0B09"))
        )
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

    LaunchedEffect(Unit) { VpnTrackerService.loadPersistedMode(context) }

    Box(Modifier.fillMaxSize().background(AshBlack)) {
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

// ── Domain match helper ───────────────────────────────────────────────────────

fun isEffectivelyBlocked(domain: String, rules: List<FilterRule>, mode: FilterMode): Boolean {
    val lower = domain.lowercase()
    val match = rules.filter { it.isEnabled }.firstOrNull { rule ->
        when (rule.matchType) {
            MatchType.EXACT     -> lower == rule.pattern.lowercase()
            MatchType.SUBDOMAIN -> lower == rule.pattern.lowercase() || lower.endsWith(".${rule.pattern.lowercase()}")
            MatchType.CONTAINS  -> lower.contains(rule.pattern.lowercase())
            MatchType.PREFIX    -> lower.startsWith(rule.pattern.lowercase())
            MatchType.SUFFIX    -> lower.endsWith(rule.pattern.lowercase())
        }
    }
    return when {
        match != null                -> match.action == RuleAction.BLOCK
        mode == FilterMode.WHITELIST -> true
        else                         -> false
    }
}

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, dao: AppDao) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

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
            title        = "Clear History",
            text         = "All connection logs will be permanently deleted.",
            confirmText  = "Clear",
            confirmColor = EmberRed,
            onConfirm    = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss    = { showClearLogsDialog = false }
        )
    }
    if (showAddGlobalRuleDialog) {
        AddRuleDialog(
            title         = "Add Global Rule",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss     = { showAddGlobalRuleDialog = false },
            onAdd         = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = null)) } }
        )
    }

    Scaffold(
        containerColor = AshBlack,
        topBar = {
            Box(Modifier.fillMaxWidth().background(AshDeep).statusBarsPadding()) {
                Column(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 5.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
//                    Text(
//                        "Menu",
//                        fontSize = 40.sp,
//                        color    = PhoenixFlameDim,
//                        textAlign = TextAlign.Center
//                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(PhoenixFlame.copy(0.7f), PhoenixFlame.copy(0.2f), Color.Transparent)
                            )
                        )
                )
            }
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(AshDeep).navigationBarsPadding()) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, PhoenixFlame.copy(0.35f), Color.Transparent)
                            )
                        )
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
                                unselectedIconColor = AshTextSecondary,
                                unselectedTextColor = AshTextSecondary,
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
                    enter = scaleIn() + fadeIn(),
                    exit  = scaleOut() + fadeOut()
                ) {
                    PhoenixFab(
                        onClick            = { showClearLogsDialog = true },
                        icon               = Icons.Default.Delete,
                        color              = EmberRed,
                        contentDescription = "Clear logs"
                    )
                }
                2 -> PhoenixFab(
                    onClick            = { showAddGlobalRuleDialog = true },
                    icon               = Icons.Default.Add,
                    color              = PhoenixFlame,
                    contentDescription = "Add rule"
                )
                else -> {}
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(AshBlack)
        ) {
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
                        PhoenixSearchField(logSearchQuery, { logSearchQuery = it }, "Search domains...")
                        if (logSearchQuery.isNotBlank()) {
                            Text(
                                "${filteredLogs.size} of ${recentLogs.size} results",
                                fontSize = 11.sp,
                                color    = PhoenixFlameDim,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                            )
                        }
                        GlobalLogList(filteredLogs, globalRules, filterMode, dao, scope)
                    }
                    2 -> GlobalRulesTab(globalRules, filterMode, context, dao, scope)
                }
            }

            FireToastCard(
                message   = toastMessage,
                visible   = showErrorToast,
                onDismiss = { showErrorToast = false },
                modifier  = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
        }
    }
}

// ── Search field ──────────────────────────────────────────────────────────────

@Composable
fun PhoenixSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(AshNavy, PhoenixShapeMedium)
            .border(1.dp, EmberBorderBright.copy(0.4f), PhoenixShapeMedium)
    ) {
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(placeholder, fontSize = 13.sp, color = AshTextSecondary) },
            leadingIcon   = {
                Icon(Icons.Default.Search, null, tint = PhoenixFlame, modifier = Modifier.size(18.dp))
            },
            trailingIcon  = {
                if (value.isNotEmpty())
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Clear, "Clear", tint = PhoenixFlameDim, modifier = Modifier.size(16.dp))
                    }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor     = AshTextPrimary,
                unfocusedTextColor   = AshTextPrimary,
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
    val borderColor = if (isRunning) PhoenixFlame else EmberBorderMid

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(AshNavy, PhoenixShapeLarge)
            .border(1.dp, borderColor.copy(0.4f), PhoenixShapeLarge)
            .clip(PhoenixShapeLarge)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingDot(if (isRunning) PhoenixFlame else AshTextSecondary, 14.dp)
                Spacer(Modifier.height(6.dp))
                Icon(
                    if (isRunning) Icons.Default.Lock else Icons.Default.LockOpen,
                    null,
                    tint     = if (isRunning) PhoenixFlame else AshTextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isRunning) "Firewall Active" else "Firewall Offline",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp,
                    color      = if (isRunning) PhoenixFlame else AshTextSecondary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isRunning) "Monitoring DNS traffic" else "Engine is stopped",
                    fontSize = 12.sp,
                    color    = if (isRunning) PhoenixFlame.copy(0.65f) else AshTextTertiary
                )
                if (!isRunning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasEnabledApps) "Ready to start"
                        else                "No apps enabled — go to Apps tab first",
                        fontSize = 11.sp,
                        color    = if (hasEnabledApps) EmberGreen.copy(0.7f) else EmberRed.copy(0.8f)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isRunning) PhoenixButton("Stop",  EmberRed,   onClick = onStop)
            else           PhoenixButton("Start", EmberGreen, onClick = onStart)
        }
    }
}

// ── App list item ─────────────────────────────────────────────────────────────

@Composable
fun AppListItem(app: ApplicationInfo, config: AppConfig?, onClick: () -> Unit) {
    val context   = LocalContext.current
    val label     = remember(app.packageName) {
        context.packageManager.getApplicationLabel(app).toString()
    }
    val isEnabled = config?.isFilteringEnabled == true

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(52.dp)
                .background(
                    Brush.verticalGradient(
                        if (isEnabled) listOf(PhoenixFlame, EmberGreen)
                        else           listOf(EmberBorderMid, EmberBorderFaint)
                    ),
                    RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(38.dp)
                .background(AshSlate, PhoenixShapeSmall)
                .border(
                    0.5.dp,
                    if (isEnabled) PhoenixFlame.copy(0.3f) else EmberBorderMid,
                    PhoenixShapeSmall
                )
                .clip(PhoenixShapeSmall)
        ) {
            AppIconImage(app.packageName, Modifier.fillMaxSize().padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = AshTextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                fontFamily = FontFamily.Monospace,
                fontSize   = 9.sp,
                color      = AshTextSecondary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
        }
        if (isEnabled) {
            EmberChip("Active", PhoenixFlame)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.Default.ChevronRight, null,
            tint     = EmberBorderBright,
            modifier = Modifier.size(16.dp)
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 31.dp)
            .height(0.5.dp)
            .background(EmberBorderFaint)
    )
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
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(AshNavy, PhoenixShapeMedium)
            .border(1.dp, PhoenixFlame.copy(0.25f), PhoenixShapeMedium)
            .padding(16.dp)
    ) {
        Column {
            Text(
                "Filter Mode",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                color      = PhoenixFlame
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    FilterMode.BLACKLIST to EmberRed,
                    FilterMode.WHITELIST to EmberGreen
                ).forEach { (mode, color) ->
                    val selected = currentMode == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) color.copy(0.12f) else AshSlate,
                                PhoenixShapeSmall
                            )
                            .border(
                                1.dp,
                                if (selected) color.copy(0.6f) else EmberBorderMid,
                                PhoenixShapeSmall
                            )
                            .clickable { onModeChange(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column {
                            Text(
                                mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontWeight = FontWeight.Bold,
                                fontSize   = 12.sp,
                                color      = if (selected) color else AshTextSecondary
                            )
                            Text(
                                if (mode == FilterMode.BLACKLIST) "Block matched, allow rest"
                                else                               "Allow matched, block rest",
                                fontSize = 10.sp,
                                color    = if (selected) color.copy(0.6f) else AshTextTertiary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (currentMode == FilterMode.BLACKLIST)
                    "All traffic is permitted unless matched by a block rule"
                else
                    "All traffic is denied unless matched by an allow rule",
                fontSize = 10.sp,
                color    = AshTextSecondary.copy(0.7f)
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
            PhoenixEmptyState(
                Icons.Default.Lock,
                if (filterMode == FilterMode.BLACKLIST)
                    "No block rules defined\nAll traffic is permitted\nTap + to add a rule"
                else
                    "No allow rules defined\nAll traffic is blocked\nTap + to add a rule"
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
        val block  = rules.filter { it.action == RuleAction.BLOCK }
        if (allow.isNotEmpty()) {
            item { PhoenixSectionHeader("Allow List", EmberGreen) }
            items(allow, key = { it.id }) { rule ->
                RuleItem(rule,
                    onDelete = { scope.launch { dao.deleteRule(rule) } },
                    onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
            }
        }
        if (block.isNotEmpty()) {
            item { PhoenixSectionHeader("Block List", EmberRed) }
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
    dao: AppDao,
    scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        PhoenixEmptyState(
            Icons.Default.List,
            "No DNS history yet\nStart the firewall to begin monitoring"
        )
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = log.isBlocked || isEffectivelyBlocked(log.domain, globalRules, filterMode)
            LogItemExtended(
                log, blocked, filterMode,
                onBlock = {
                    scope.launch {
                        dao.insertRule(FilterRule(
                            packageName = null,
                            pattern     = log.domain,
                            matchType   = MatchType.SUBDOMAIN,
                            action      = RuleAction.BLOCK
                        ))
                    }
                },
                onAllow = {
                    scope.launch {
                        dao.insertRule(FilterRule(
                            packageName = null,
                            pattern     = log.domain,
                            matchType   = MatchType.SUBDOMAIN,
                            action      = RuleAction.ALLOW
                        ))
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
        PhoenixEmptyState(
            Icons.Default.Info,
            "No DNS activity yet\nEnable firewall for this app\nthen start the engine"
        )
        return
    }
    LazyColumn {
        items(logs, key = { it.id }) { log ->
            val blocked = log.isBlocked || isEffectivelyBlocked(log.domain, allRules, filterMode)
            LogItemExtended(
                log, blocked, filterMode,
                onBlock = {
                    scope.launch {
                        dao.insertRule(FilterRule(
                            packageName = packageName,
                            pattern     = log.domain,
                            matchType   = MatchType.SUBDOMAIN,
                            action      = RuleAction.BLOCK
                        ))
                    }
                },
                onAllow = {
                    scope.launch {
                        dao.insertRule(FilterRule(
                            packageName = packageName,
                            pattern     = log.domain,
                            matchType   = MatchType.SUBDOMAIN,
                            action      = RuleAction.ALLOW
                        ))
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
    isBlocked: Boolean,
    filterMode: FilterMode,
    onBlock: () -> Unit,
    onAllow: () -> Unit
) {
    val accentColor = when {
        isBlocked                          -> EmberRed
        filterMode == FilterMode.WHITELIST -> EmberGreen
        else                               -> EmberBorderMid
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
                .background(
                    accentColor.copy(
                        if (isBlocked || filterMode == FilterMode.WHITELIST) 0.8f else 0.2f
                    ),
                    RoundedCornerShape(1.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                log.domain,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize   = 12.sp,
                color      = AshTextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                remember(log.timestamp) { logDateFormat.format(Date(log.timestamp)) },
                fontFamily = FontFamily.Monospace,
                fontSize   = 9.sp,
                color      = AshTextSecondary
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            isBlocked && filterMode == FilterMode.WHITELIST ->
                EmberTextButton("Allow", EmberGreen, onAllow)
            isBlocked ->
                EmberChip("Blocked", EmberRed)
            filterMode == FilterMode.WHITELIST ->
                EmberChip("Allowed", EmberGreen)
            else ->
                EmberTextButton("Block", EmberRed, onBlock)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 30.dp)
            .height(0.5.dp)
            .background(EmberBorderFaint)
    )
}

// ── Rule item ─────────────────────────────────────────────────────────────────

@Composable
fun RuleItem(rule: FilterRule, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    val color = if (rule.action == RuleAction.BLOCK) EmberRed else EmberGreen

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
                .background(
                    color.copy(if (rule.isEnabled) 0.8f else 0.3f),
                    RoundedCornerShape(1.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberChip(rule.matchType.displayName(), color)
                Spacer(Modifier.width(8.dp))
                Text(
                    rule.pattern,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp,
                    color      = AshTextPrimary,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${if (rule.action == RuleAction.BLOCK) "Block" else "Allow"} · ${rule.matchType.description()}",
                fontSize = 10.sp,
                color    = AshTextSecondary
            )
        }
        Switch(
            checked         = rule.isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = VoidBlack,
                checkedTrackColor   = color.copy(0.8f),
                uncheckedThumbColor = AshTextTertiary,
                uncheckedTrackColor = AshSlate
            )
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete, "Delete",
                tint     = EmberRed.copy(0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 30.dp)
            .height(0.5.dp)
            .background(EmberBorderFaint)
    )
}

// ── App detail screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(navController: NavController, dao: AppDao, packageName: String) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

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
            title         = "Add Rule — $label",
            initialAction = if (filterMode == FilterMode.WHITELIST) RuleAction.ALLOW else RuleAction.BLOCK,
            onDismiss     = { showAddRuleDialog = false },
            onAdd         = { rule -> scope.launch { dao.insertRule(rule.copy(packageName = packageName)) } }
        )
    }
    if (showClearLogsDialog) {
        PhoenixAlertDialog(
            title        = "Clear Logs",
            text         = "All DNS activity logs will be permanently deleted.",
            confirmText  = "Clear",
            confirmColor = EmberRed,
            onConfirm    = { scope.launch { dao.clearLogs() }; showClearLogsDialog = false },
            onDismiss    = { showClearLogsDialog = false }
        )
    }

    Scaffold(
        containerColor = AshBlack,
        topBar = {
            Box(Modifier.fillMaxWidth().background(AshDeep).statusBarsPadding()) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = PhoenixFlame)
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(AshSlate, PhoenixShapeSmall)
                            .border(0.5.dp, PhoenixFlame.copy(0.3f), PhoenixShapeSmall)
                            .clip(PhoenixShapeSmall)
                    ) {
                        AppIconImage(packageName, Modifier.fillMaxSize().padding(3.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            label,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 15.sp,
                            color      = PhoenixFlame,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        Text(
                            packageName,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 9.sp,
                            color      = AshTextSecondary,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(PhoenixFlame.copy(0.5f), PhoenixFlame.copy(0.1f), Color.Transparent)
                            )
                        )
                )
            }
        },
        floatingActionButton = {
            when (selectedSection) {
                0 -> AnimatedVisibility(
                    allLogs.isNotEmpty(),
                    enter = scaleIn() + fadeIn(),
                    exit  = scaleOut() + fadeOut()
                ) {
                    PhoenixFab(
                        onClick            = { showClearLogsDialog = true },
                        icon               = Icons.Default.Delete,
                        color              = EmberRed,
                        contentDescription = "Clear logs"
                    )
                }
                1 -> PhoenixFab(
                    onClick            = { showAddRuleDialog = true },
                    icon               = Icons.Default.Add,
                    color              = PhoenixFlame,
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
                .background(AshBlack)
        ) {
            // Firewall toggle
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(AshNavy, PhoenixShapeMedium)
                    .border(
                        1.dp,
                        if (appConfig?.isFilteringEnabled == true) EmberGreen.copy(0.35f)
                        else EmberBorderMid,
                        PhoenixShapeMedium
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Firewall Intercept",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp,
                            color      = PhoenixFlame
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (appConfig?.isFilteringEnabled == true)
                                "DNS filtering is active"
                            else
                                "App bypasses the firewall",
                            fontSize = 11.sp,
                            color    = if (appConfig?.isFilteringEnabled == true)
                                EmberGreen.copy(0.7f) else AshTextSecondary
                        )
                    }
                    Switch(
                        checked         = appConfig?.isFilteringEnabled ?: false,
                        onCheckedChange = { enabled ->
                            scope.launch { dao.insertAppConfig(AppConfig(packageName, label, enabled)) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = VoidBlack,
                            checkedTrackColor   = EmberGreen.copy(0.8f),
                            uncheckedThumbColor = AshTextTertiary,
                            uncheckedTrackColor = AshSlate
                        )
                    )
                }
            }

            // Tab selector
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(AshNavy, PhoenixShapeSmall)
                    .border(1.dp, EmberBorderMid, PhoenixShapeSmall)
                    .padding(4.dp)
            ) {
                listOf("DNS Activity", "App Rules").forEachIndexed { idx, tabLabel ->
                    val selected = selectedSection == idx
                    val display  = if (idx == 1 && appRules.isNotEmpty())
                        "$tabLabel  ${appRules.size}" else tabLabel
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) PhoenixFlameGhost else Color.Transparent,
                                PhoenixShapeSmall
                            )
                            .border(
                                if (selected) 1.dp else 0.dp,
                                if (selected) PhoenixFlame.copy(0.4f) else Color.Transparent,
                                PhoenixShapeSmall
                            )
                            .clickable { selectedSection = idx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            display,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 12.sp,
                            color      = if (selected) PhoenixFlame else AshTextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (selectedSection == 0) {
                PhoenixSearchField(logSearchQuery, { logSearchQuery = it }, "Search domains...")
                if (logSearchQuery.isNotBlank()) {
                    Text(
                        "${filteredLogs.size} of ${allLogs.size} results",
                        fontSize = 11.sp,
                        color    = PhoenixFlameDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )
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
fun AppRulesList(
    rules: List<FilterRule>,
    appLabel: String,
    globalCount: Int,
    dao: AppDao,
    scope: CoroutineScope
) {
    Column(Modifier.fillMaxSize()) {
        if (globalCount > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(PhoenixGoldGhost, PhoenixShapeSmall)
                    .border(1.dp, PhoenixGold.copy(0.25f), PhoenixShapeSmall)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, Modifier.size(14.dp), tint = PhoenixGold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$globalCount global rule${if (globalCount != 1) "s" else ""} also apply to this app",
                    fontSize = 11.sp,
                    color    = PhoenixGold.copy(0.85f)
                )
            }
        }
        if (rules.isEmpty()) {
            PhoenixEmptyState(
                Icons.Default.Lock,
                "No app-specific rules\nTap + to add one\nor tap Block in DNS Activity"
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                val allow = rules.filter { it.action == RuleAction.ALLOW }
                val block  = rules.filter { it.action == RuleAction.BLOCK }
                if (allow.isNotEmpty()) {
                    item { PhoenixSectionHeader("Allow Rules", EmberGreen) }
                    items(allow, key = { it.id }) { rule ->
                        RuleItem(rule,
                            onDelete = { scope.launch { dao.deleteRule(rule) } },
                            onToggle = { scope.launch { dao.setRuleEnabled(rule.id, it) } })
                    }
                }
                if (block.isNotEmpty()) {
                    item { PhoenixSectionHeader("Block Rules", EmberRed) }
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
    onDismiss: () -> Unit,
    onAdd: (FilterRule) -> Unit
) {
    var pattern      by remember { mutableStateOf("") }
    var matchType    by remember { mutableStateOf(MatchType.SUBDOMAIN) }
    var action       by remember { mutableStateOf(initialAction) }
    var menuExpanded by remember { mutableStateOf(false) }
    val trimmed = pattern.trim().lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = AshDeep,
        tonalElevation   = 0.dp,
        shape            = PhoenixShapeLarge,
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = PhoenixFlame)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RuleAction.BLOCK to EmberRed, RuleAction.ALLOW to EmberGreen)
                        .forEach { (a, color) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .background(
                                        if (action == a) color.copy(0.12f) else AshNavy,
                                        PhoenixShapeSmall
                                    )
                                    .border(
                                        1.dp,
                                        if (action == a) color.copy(0.7f) else EmberBorderMid,
                                        PhoenixShapeSmall
                                    )
                                    .clickable { action = a }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    a.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.Bold,
                                    fontSize   = 12.sp,
                                    color      = if (action == a) color else AshTextSecondary
                                )
                            }
                        }
                }
                OutlinedTextField(
                    value         = pattern,
                    onValueChange = { pattern = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    label         = { Text("Pattern", fontSize = 12.sp) },
                    placeholder   = {
                        Text(
                            "example.com",
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp,
                            color      = AshTextTertiary
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = PhoenixFlame.copy(0.6f),
                        unfocusedBorderColor = EmberBorderMid,
                        focusedTextColor     = AshTextPrimary,
                        unfocusedTextColor   = AshTextPrimary,
                        cursorColor          = PhoenixFlame,
                        focusedLabelColor    = PhoenixFlame,
                        unfocusedLabelColor  = AshTextSecondary
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 13.sp
                    )
                )
                ExposedDropdownMenuBox(
                    expanded         = menuExpanded,
                    onExpandedChange = { menuExpanded = it }
                ) {
                    OutlinedTextField(
                        value         = "${matchType.displayName()} — ${matchType.description()}",
                        onValueChange = {},
                        readOnly      = true,
                        modifier      = Modifier.menuAnchor().fillMaxWidth(),
                        label         = { Text("Match Type", fontSize = 12.sp) },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = PhoenixFlame.copy(0.6f),
                            unfocusedBorderColor = EmberBorderMid,
                            focusedTextColor     = AshTextPrimary,
                            unfocusedTextColor   = AshTextPrimary,
                            focusedLabelColor    = PhoenixFlame,
                            unfocusedLabelColor  = AshTextSecondary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                    ExposedDropdownMenu(
                        expanded         = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier         = Modifier.background(AshNavy)
                    ) {
                        MatchType.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            type.displayName(),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize   = 13.sp,
                                            color      = PhoenixFlame
                                        )
                                        Text(
                                            type.description(),
                                            fontSize = 11.sp,
                                            color    = AshTextSecondary
                                        )
                                    }
                                },
                                onClick = { matchType = type; menuExpanded = false },
                                colors  = MenuDefaults.itemColors(textColor = AshTextPrimary)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val btnColor = if (action == RuleAction.BLOCK) EmberRed else EmberGreen
            PhoenixButton(
                text    = if (action == RuleAction.BLOCK) "Block" else "Allow",
                color   = btnColor,
                enabled = trimmed.isNotBlank(),
                onClick = {
                    onAdd(FilterRule(
                        packageName = null,
                        pattern     = trimmed,
                        matchType   = matchType,
                        action      = action
                    ))
                    onDismiss()
                }
            )
        },
        dismissButton = {
            PhoenixButton("Cancel", AshTextSecondary, onClick = onDismiss)
        }
    )
}

// ── Alert dialog ──────────────────────────────────────────────────────────────

@Composable
fun PhoenixAlertDialog(
    title: String,
    text: String,
    confirmText: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = AshDeep,
        tonalElevation   = 0.dp,
        shape            = PhoenixShapeLarge,
        title = { Text(title, fontWeight = FontWeight.Bold, color = confirmColor) },
        text  = { Text(text, fontSize = 13.sp, color = AshTextSecondary) },
        confirmButton = { PhoenixButton(confirmText, confirmColor, onClick = onConfirm) },
        dismissButton = { PhoenixButton("Cancel", AshTextSecondary, onClick = onDismiss) }
    )
}

// ── Primitive components ──────────────────────────────────────────────────────

@Composable
fun PhoenixButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(color.copy(if (enabled) 0.12f else 0.05f), PhoenixShapeSmall)
            .border(1.dp, color.copy(if (enabled) 0.65f else 0.2f), PhoenixShapeSmall)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 12.sp,
            color      = color.copy(if (enabled) 1f else 0.4f)
        )
    }
}

@Composable
fun EmberTextButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .border(0.5.dp, color.copy(0.45f), PhoenixShapeChip)
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
    Box(
        Modifier
            .size(52.dp)
            .background(color.copy(0.12f), PhoenixShapeMedium)
            .border(1.dp, color.copy(0.65f), PhoenixShapeMedium)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PhoenixEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(52.dp), tint = PhoenixFlame.copy(0.15f))
        Spacer(Modifier.height(20.dp))
        Text(
            message,
            textAlign  = TextAlign.Center,
            fontSize   = 13.sp,
            lineHeight = 20.sp,
            color      = AshTextSecondary.copy(0.7f)
        )
    }
}