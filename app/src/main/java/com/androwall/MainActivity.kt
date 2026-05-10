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
    val navController = rememberNavController()
    val context = LocalContext.current
    val dao = AppDatabase.getDatabase(context).appDao()

    NavHost(navController, startDestination = "main") {
        composable("main") { MainScreen(navController, dao) }
        composable(
            "app_detail/{packageName}",
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { back ->
            val pkg = back.arguments?.getString("packageName") ?: ""
            AppDetailScreen(navController, dao, pkg)
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

    // Collect global blocked domains so History tab shows BLOCKED immediately after user blocks
    val globalBlockedDomains by dao.getGlobalBlockedDomains().collectAsState(initial = emptyList())
    val globalBlockedSet = remember(globalBlockedDomains) {
        globalBlockedDomains.map { it.domain.lowercase() }.toSet()
    }

    var installedApps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

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

    // Set of packages that have filtering on — re-derived whenever configs change
    val enabledPackages = remember(appConfigs) {
        appConfigs.filter { it.isFilteringEnabled }.map { it.packageName }.toSet()
    }

    // Enabled apps float to the top; both lists re-sort when configs or search changes
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
            dismissButton = {
                OutlinedButton(onClick = { showClearLogsDialog = false }) { Text("Cancel") }
            }
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
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedTab == 1 && recentLogs.isNotEmpty(),
                enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = { showClearLogsDialog = true },
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ) {
                    Icon(Icons.Default.Delete, "Clear logs", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
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
                onStop = { context.stopService(Intent(context, VpnTrackerService::class.java)) }
            )

            if (selectedTab == 0) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search apps…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty())
                            IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, "Clear") }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                LazyColumn(Modifier.weight(1f)) {
                    if (filteredApps.isEmpty()) {
                        item { EmptyState(Icons.Default.Search, "No apps match \"$searchQuery\".") }
                    } else {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val config = appConfigs.find { it.packageName == app.packageName }
                            AppListItem(app, config) {
                                navController.navigate("app_detail/${app.packageName}")
                            }
                        }
                    }
                }
            } else {
                // globalBlockedSet lets log rows flip to BLOCKED instantly after user taps BLOCK
                GlobalLogList(recentLogs, globalBlockedSet, dao, scope)
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
                ),
                contentAlignment = Alignment.Center
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
                if (!isRunning) {
                    Text(
                        "Enable filtering on at least one app first.",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }
            }
            if (isRunning) {
                Button(
                    onClick = onStop,
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
    val label = remember(app.packageName) {
        context.packageManager.getApplicationLabel(app).toString()
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        headlineContent = { Text(label, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(app.packageName, fontSize = 12.sp) },
        leadingContent = { AppIconImage(app.packageName, Modifier.size(40.dp)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (config?.isFilteringEnabled == true) {
                    Icon(
                        Icons.Default.CheckCircle, "Filtering active",
                        tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

// ── App icon (real launcher icon) ─────────────────────────────────────────────

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

    // Per-app blocked entries shown in the Blocked tab
    val appBlockedDomains by dao.getBlockedDomainsForApp(packageName).collectAsState(initial = emptyList())
    // Global blocked entries (packageName IS NULL in DB)
    val globalBlockedDomains by dao.getGlobalBlockedDomains().collectAsState(initial = emptyList())

    // Combined set: if a domain is in either list, log rows show BLOCKED immediately
    val allBlockedSet = remember(appBlockedDomains, globalBlockedDomains) {
        (appBlockedDomains + globalBlockedDomains).map { it.domain.lowercase() }.toSet()
    }

    // All DNS logs — per-app tracking isn't possible without UID inspection (root),
    // so we show all intercepted DNS queries and let the user block per-app from here.
    val allLogs by dao.getRecentLogs().collectAsState(initial = emptyList())

    var selectedSection by remember { mutableIntStateOf(0) }
    var showAddDomainDialog by remember { mutableStateOf(false) }

    if (showAddDomainDialog) {
        AddDomainDialog(
            title = "Block domain for $label",
            onDismiss = { showAddDomainDialog = false },
            onAdd = { domain ->
                scope.launch {
                    dao.insertBlockedDomain(BlockedDomain(packageName = packageName, domain = domain))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = { AppIconImage(packageName, Modifier.size(32.dp)) }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedSection == 1,
                enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(onClick = { showAddDomainDialog = true }) {
                    Icon(Icons.Default.Add, "Add blocked domain")
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
                            if (appConfig?.isFilteringEnabled == true) "DNS filtering active"
                            else "App bypasses firewall",
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
                Tab(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    text = { Text("DNS Activity") }
                )
                Tab(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    text = {
                        BadgedBox(
                            badge = {
                                if (appBlockedDomains.isNotEmpty())
                                    Badge { Text("${appBlockedDomains.size}") }
                            }
                        ) { Text("Blocked") }
                    }
                )
            }

            if (selectedSection == 0) {
                // allBlockedSet → rows immediately show BLOCKED when user taps BLOCK
                // packageName  → BLOCK button creates a per-app rule, not a global one
                AppTrafficList(allLogs, allBlockedSet, packageName, dao, scope)
            } else {
                BlockedDomainsList(appBlockedDomains, dao, scope)
            }
        }
    }
}

// ── Blocked domains list ──────────────────────────────────────────────────────

@Composable
fun BlockedDomainsList(domains: List<BlockedDomain>, dao: AppDao, scope: CoroutineScope) {
    if (domains.isEmpty()) {
        EmptyState(
            Icons.Default.Lock,
            "No domains blocked for this app.\nTap + to add one, or tap BLOCK on any DNS Activity row."
        )
    } else {
        LazyColumn {
            items(domains, key = { it.id }) { domain ->
                ListItem(
                    headlineContent = { Text(domain.domain, fontWeight = FontWeight.Medium) },
                    leadingContent = {
                        Icon(Icons.Default.Close, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp))
                    },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { dao.deleteBlockedDomain(domain) } }) {
                            Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}

// ── Traffic / log lists ───────────────────────────────────────────────────────

@Composable
fun AppTrafficList(
    logs: List<ConnectionLog>,
    currentlyBlockedDomains: Set<String>,   // live set — row flips to BLOCKED immediately
    packageName: String,                     // BLOCK creates a per-app rule for this package
    dao: AppDao,
    scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        EmptyState(
            Icons.Default.Info,
            "No DNS activity yet.\nMake sure the firewall is running and this app is enabled."
        )
    } else {
        LazyColumn {
            items(logs, key = { it.id }) { log ->
                LogItemExtended(log, currentlyBlockedDomains) {
                    scope.launch {
                        dao.insertBlockedDomain(BlockedDomain(packageName = packageName, domain = log.domain))
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalLogList(
    logs: List<ConnectionLog>,
    currentlyBlockedDomains: Set<String>,   // live global block set
    dao: AppDao,
    scope: CoroutineScope
) {
    if (logs.isEmpty()) {
        EmptyState(
            Icons.Default.List,
            "No DNS history yet.\nStart the firewall to begin monitoring."
        )
    } else {
        LazyColumn {
            items(logs, key = { it.id }) { log ->
                LogItemExtended(log, currentlyBlockedDomains) {
                    scope.launch {
                        // History tab blocks globally (null packageName = affects all enabled apps)
                        dao.insertBlockedDomain(BlockedDomain(packageName = null, domain = log.domain))
                    }
                }
            }
        }
    }
}

// ── Log item ──────────────────────────────────────────────────────────────────

private val logDateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

@Composable
fun LogItemExtended(
    log: ConnectionLog,
    currentlyBlockedDomains: Set<String> = emptySet(),
    onBlock: () -> Unit
) {
    // Show BLOCKED if the VPN blocked it at intercept time, OR if user has since added it to a block list
    val isEffectivelyBlocked = log.isBlocked || log.domain.lowercase() in currentlyBlockedDomains

    ListItem(
        headlineContent = { Text(log.domain, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                remember(log.timestamp) { logDateFormat.format(Date(log.timestamp)) },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        },
        trailingContent = {
            if (isEffectivelyBlocked) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "BLOCKED",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            } else {
                TextButton(onClick = onBlock) { Text("BLOCK") }
            }
        }
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

// ── Add domain dialog ─────────────────────────────────────────────────────────

@Composable
fun AddDomainDialog(title: String = "Block Domain", onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim().lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Close, null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Domain") },
                placeholder = { Text("example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onAdd(trimmed); onDismiss() },
                enabled = trimmed.isNotBlank() && trimmed.contains('.')
            ) { Text("Block") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Spacer(Modifier.height(20.dp))
        Text(
            message, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 14.sp, lineHeight = 20.sp
        )
    }
}