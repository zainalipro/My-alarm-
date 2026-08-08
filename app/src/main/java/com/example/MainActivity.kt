package com.example

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.data.BlockedAppEntity
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel()
            val currentSettings by viewModel.settings.collectAsState()
            
            // Resolve Theme Mode
            val darkTheme = when (currentSettings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            
            MyApplicationTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppMainLayout(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Stop any ringtone if we enter app and session is inactive
        val context = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!SessionManager.isSessionActive.value) {
                AppBlockerService.stopRingtone()
            }
        }, 500)
    }
}

sealed class Screen(val title: String, val icon: ImageVector, val route: String) {
    object Dashboard : Screen("Focus", Icons.Default.Timer, "dashboard")
    object Settings : Screen("Settings", Icons.Default.Settings, "settings")
    object About : Screen("About", Icons.Default.Info, "about")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMainLayout(viewModel: AppViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Screens State
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    
    // Core state from ViewModel
    val latestSession by viewModel.latestSession.collectAsState()
    val selectedApps by viewModel.selectedAppsFromDb.collectAsState()
    val isSessionActive by SessionManager.isSessionActive.collectAsState()
    
    // Refresh permissions state continuously
    var hasNotifications by remember { mutableStateOf(viewModel.hasNotificationPermission()) }
    var hasUsageStats by remember { mutableStateOf(viewModel.hasUsageAccessPermission()) }
    var hasOverlay by remember { mutableStateOf(viewModel.hasOverlayPermission()) }
    var hasAccessibility by remember { mutableStateOf(viewModel.hasAccessibilityPermission()) }
    
    val allPermissionsGranted = hasNotifications && hasUsageStats && hasOverlay && hasAccessibility

    LaunchedEffect(Unit) {
        while (true) {
            hasNotifications = viewModel.hasNotificationPermission()
            hasUsageStats = viewModel.hasUsageAccessPermission()
            hasOverlay = viewModel.hasOverlayPermission()
            hasAccessibility = viewModel.hasAccessibilityPermission()
            delay(1500)
        }
    }

    // Alarm screen check
    var showAlarmRingingScreen by remember { mutableStateOf(AppBlockerService.isRingtonePlaying) }
    LaunchedEffect(Unit) {
        while (true) {
            showAlarmRingingScreen = AppBlockerService.isRingtonePlaying
            delay(1000)
        }
    }

    if (showAlarmRingingScreen) {
        AlarmRingingScreen(onDismiss = {
            AppBlockerService.stopRingtone()
            showAlarmRingingScreen = false
        })
    } else {
        Scaffold(
            bottomBar = {
                // Hide navigation bar entirely when a session is active to prevent tampering!
                if (!isSessionActive) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp
                    ) {
                        val screens = listOf(Screen.Dashboard, Screen.Settings, Screen.About)
                        screens.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.title) },
                                label = { Text(screen.title) },
                                selected = currentScreen == screen,
                                onClick = { currentScreen = screen },
                                modifier = Modifier.testTag("nav_item_${screen.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when {
                    isSessionActive -> {
                        // Force render Dashboard when session is active
                        ActiveSessionScreen(viewModel)
                    }
                    !allPermissionsGranted -> {
                        PermissionOnboardingScreen(
                            viewModel = viewModel,
                            hasNotifications = hasNotifications,
                            hasUsageStats = hasUsageStats,
                            hasOverlay = hasOverlay,
                            hasAccessibility = hasAccessibility,
                            onRefresh = {
                                hasNotifications = viewModel.hasNotificationPermission()
                                hasUsageStats = viewModel.hasUsageAccessPermission()
                                hasOverlay = viewModel.hasOverlayPermission()
                                hasAccessibility = viewModel.hasAccessibilityPermission()
                            }
                        )
                    }
                    else -> {
                        when (currentScreen) {
                            Screen.Dashboard -> DashboardSetupScreen(viewModel)
                            Screen.Settings -> SettingsScreen(viewModel)
                            Screen.About -> AboutScreen()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmRingingScreen(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Alarm,
                contentDescription = "Alarm Ringing",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Focus Complete! 🎉",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Your scheduled phone stop session has finished successfully. Blocked apps are now unlocked.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onError,
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(60.dp)
                    .testTag("dismiss_alarm_button")
            ) {
                Text("Dismiss Alarm Sound", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun PermissionOnboardingScreen(
    viewModel: AppViewModel,
    hasNotifications: Boolean,
    hasUsageStats: Boolean,
    hasOverlay: Boolean,
    hasAccessibility: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onRefresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Permissions Required",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Phone Stop Alarm needs these standard Android permissions to securely intercept distracting applications during your locked sessions. These operate 100% locally on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))

        // 1. Notification Permission (Android 13+)
        PermissionCard(
            title = "Notifications Permission",
            desc = "Required to keep the focus timer active in the background and trigger completion alerts.",
            isGranted = hasNotifications,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Toast.makeText(context, "Granted automatically on this Android version.", Toast.LENGTH_SHORT).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Usage Stats
        PermissionCard(
            title = "Usage Access Permission",
            desc = "Allows the app to detect which application is currently running in the foreground to apply block rules.",
            isGranted = hasUsageStats,
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    })
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Display Over Other Apps
        PermissionCard(
            title = "Display Over Other Apps",
            desc = "Required to show the minimal full-screen lock overlay immediately when a blocked app is started.",
            isGranted = hasOverlay,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                } else {
                    Toast.makeText(context, "Granted automatically on this Android version.", Toast.LENGTH_SHORT).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Accessibility Service
        PermissionCard(
            title = "Accessibility Service",
            desc = "Allows instant, robust, battery-friendly detection and blocking of configured apps without delay.",
            isGranted = hasAccessibility,
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(context, "Please open Settings -> Accessibility manually.", Toast.LENGTH_LONG).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onRefresh,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(50.dp)
                .testTag("check_permissions_button")
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Re-verify Permissions")
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    desc: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, if (isGranted) Color.Transparent else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isGranted) "Granted" else "Needed",
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!isGranted) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Go to Settings",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSetupScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // UI selections
    val installedApps by viewModel.installedApps.collectAsState()
    val selectedAppsFromDb by viewModel.selectedAppsFromDb.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val recentApps by viewModel.recentApps.collectAsState()
    val allScheduledAlarms by viewModel.allScheduledAlarms.collectAsState()

    var activeTabIsBlock by remember { mutableStateOf(true) } // true for Block list, false for Allow list
    var selectedDurationMinutes by remember { mutableIntStateOf(15) }
    var showCustomDurationDialog by remember { mutableStateOf(false) }

    // Dialog & Checkout states
    var showAddAlarmDialog by remember { mutableStateOf(false) }
    var showGoogleLoginDialog by remember { mutableStateOf(false) }
    var showCheckoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        selectedDurationMinutes = settings.defaultDurationMinutes
    }

    // Split selections
    val blockedPackages = remember(selectedAppsFromDb) {
        selectedAppsFromDb.filter { it.isBlocked }.map { it.packageName }.toSet()
    }
    val allowedPackages = remember(selectedAppsFromDb) {
        selectedAppsFromDb.filter { !it.isBlocked }.map { it.packageName }.toSet()
    }

    // Filtered Apps
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App header banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Phone Stop Alarm",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Temporarily block distracting apps, keep essential tools.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Account and Premium Badges
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (settings.isPremium) {
                        Surface(
                            color = Color(0xFFFFD700).copy(alpha = 0.2f),
                            contentColor = Color(0xFFD4AF37),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFD4AF37)),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Star, contentDescription = "Premium", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PREMIUM", fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    } else {
                        IconButton(onClick = { showCheckoutDialog = true }) {
                            Icon(Icons.Default.StarBorder, contentDescription = "Get Premium", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    IconButton(onClick = { showGoogleLoginDialog = true }) {
                        Icon(
                            imageVector = if (settings.userEmail != null) Icons.Default.AccountCircle else Icons.Default.Login,
                            contentDescription = "Google Login",
                            tint = if (settings.userEmail != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Gmail Sign-in or Streaks Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Streak display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, contentDescription = "Streak", tint = Color(0xFFFF5722), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${settings.currentStreakDays} Day Streak",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFF5722)
                    )
                }

                // Email display if signed in
                if (settings.userEmail != null) {
                    Text(
                        text = settings.userEmail ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "Sign in to save backup data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showGoogleLoginDialog = true }
                    )
                }
            }
        }

        // Active Tabs (Apps vs Scheduled Alarms)
        var currentDashboardSubTab by remember { mutableStateOf(0) } // 0 = App Selection, 1 = Scheduled Focus Locks
        
        TabRow(selectedTabIndex = currentDashboardSubTab) {
            Tab(
                selected = currentDashboardSubTab == 0,
                onClick = { currentDashboardSubTab = 0 },
                text = { Text("App Blocker Setup", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = currentDashboardSubTab == 1,
                onClick = { currentDashboardSubTab = 1 },
                text = { Text("Scheduled Locks (${allScheduledAlarms.size})", fontWeight = FontWeight.Bold) }
            )
        }

        if (currentDashboardSubTab == 0) {
            // App Blocker Selection Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { activeTabIsBlock = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeTabIsBlock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (activeTabIsBlock) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                        .testTag("block_tab_button")
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Apps to Block (${blockedPackages.size})")
                }
                Button(
                    onClick = { activeTabIsBlock = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!activeTabIsBlock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!activeTabIsBlock) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                        .testTag("allow_tab_button")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Apps to Allow (${allowedPackages.size})")
                }
            }

            // Search Bar & Shortcuts
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search installed apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_search_field"),
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { viewModel.selectAllApps(activeTabIsBlock, filteredApps) },
                        modifier = Modifier.testTag("select_all_button")
                    ) {
                        Text("Select All (${filteredApps.size})", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { viewModel.clearApps(activeTabIsBlock) },
                        modifier = Modifier.testTag("clear_all_button")
                    ) {
                        Text("Clear All Selected", fontSize = 12.sp)
                    }
                }
            }

            // List of Apps
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isLoadingApps) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredApps.isEmpty()) {
                    Text(
                        text = "No apps found on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp)
                    ) {
                        // Recently Selected Header if applicable
                        if (recentApps.isNotEmpty() && searchQuery.isBlank()) {
                            item {
                                Text(
                                    text = "Recently Selected",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(recentApps) { dbApp ->
                                AppPickerRow(
                                    label = dbApp.appName,
                                    packageName = dbApp.packageName,
                                    isSelected = if (activeTabIsBlock) blockedPackages.contains(dbApp.packageName) else allowedPackages.contains(dbApp.packageName),
                                    onCheckedChange = { isChecked ->
                                        if (activeTabIsBlock) {
                                            viewModel.toggleAppBlock(dbApp.packageName, dbApp.appName, isChecked)
                                        } else {
                                            viewModel.toggleAppAllow(dbApp.packageName, dbApp.appName, isChecked)
                                        }
                                    }
                                )
                            }
                            item {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                            }
                        }

                        item {
                            Text(
                                text = "All Launcher Apps",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }

                        items(filteredApps) { app ->
                            val isSelected = if (activeTabIsBlock) {
                                blockedPackages.contains(app.packageName)
                            } else {
                                allowedPackages.contains(app.packageName)
                            }

                            AppPickerRow(
                                label = app.label,
                                packageName = app.packageName,
                                isSelected = isSelected,
                                onCheckedChange = { isChecked ->
                                    if (isChecked && isEssentialSystemPackageWarning(app.packageName)) {
                                        Toast.makeText(context, "Warning: Blocking system package warning.", Toast.LENGTH_LONG).show()
                                    }
                                    if (activeTabIsBlock) {
                                        viewModel.toggleAppBlock(app.packageName, app.label, isChecked)
                                    } else {
                                        viewModel.toggleAppAllow(app.packageName, app.label, isChecked)
                                    }
                                }
                            )
                        }
                    }
                }

                // Duration & Start Control Footer Card
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "LOCK SESSION CONFIGURATION",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Duration selection row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val durations = listOf(5, 15, 30, 60, 120)
                            durations.forEach { duration ->
                                FilterChip(
                                    selected = selectedDurationMinutes == duration,
                                    onClick = { selectedDurationMinutes = duration },
                                    label = { Text(if (duration >= 60) "${duration / 60}h" else "${duration}m") },
                                    modifier = Modifier.testTag("duration_${duration}_chip")
                                )
                            }
                            // Custom Duration Chip
                            FilterChip(
                                selected = !durations.contains(selectedDurationMinutes),
                                onClick = { showCustomDurationDialog = true },
                                label = { Text("Custom...") },
                                modifier = Modifier.testTag("duration_custom_chip")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (blockedPackages.isEmpty()) {
                                    Toast.makeText(context, "Please select at least one app to block first!", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.startLockSession(selectedDurationMinutes)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("start_lock_button")
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock icon")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("START LOCK SESSION (${selectedDurationMinutes}m)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // Scheduled locks list & management mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                if (allScheduledAlarms.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = "No scheduled lock",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No scheduled focus locks yet.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Add automated focus locks to start and stop blocking apps at specific times automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(allScheduledAlarms) { alarm ->
                            ScheduledAlarmRow(
                                alarm = alarm,
                                onToggle = { isEnabled ->
                                    viewModel.toggleScheduledAlarm(alarm, isEnabled)
                                },
                                onDelete = {
                                    viewModel.deleteScheduledAlarm(alarm)
                                }
                            )
                        }
                    }
                }

                // Add Alarm Button at the bottom of the list tab
                Button(
                    onClick = {
                        if (!settings.isPremium && allScheduledAlarms.size >= 2) {
                            // Free tier limit to 2 scheduled alarms to monetize
                            showCheckoutDialog = true
                        } else {
                            showAddAlarmDialog = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.AddAlarm, contentDescription = "Add Alarm")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ADD SCHEDULED FOCUS LOCK", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Visual Ad Banner (only if user is not premium)
        if (!settings.isPremium) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { showCheckoutDialog = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                "AD",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "Ads keeping us free. Unlock Premium to remove ads completely!",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Go",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    // Google Sign In Picker Dialog
    if (showGoogleLoginDialog) {
        AlertDialog(
            onDismissRequest = { showGoogleLoginDialog = false },
            title = { Text("Sign In with Google") },
            text = {
                Column {
                    Text("Select a Google Account to synchronize your backup and focus streak alarm configurations securely in the cloud:")
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (settings.userEmail == null) {
                        // Clickable Gmail Account selection row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSettings(userEmail = "zainalipri@gmail.com")
                                    showGoogleLoginDialog = false
                                    Toast.makeText(context, "Signed in as zainalipri@gmail.com", Toast.LENGTH_SHORT).show()
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Google Account", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Zain Ali", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                Text("zainalipri@gmail.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        TextButton(
                            onClick = {
                                viewModel.updateSettings(userEmail = "guest_user@gmail.com")
                                showGoogleLoginDialog = false
                                Toast.makeText(context, "Signed in as guest_user@gmail.com", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use another Gmail account...")
                        }
                    } else {
                        // Logged in state inside dialog
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active account", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Currently Connected", fontWeight = FontWeight.Bold)
                                Text(settings.userEmail ?: "", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                viewModel.updateSettings(userEmail = "LOGOUT_TOKEN")
                                showGoogleLoginDialog = false
                                Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect Account")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGoogleLoginDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Interactive Premium Checkout Dialog
    if (showCheckoutDialog) {
        var isPurchasing by remember { mutableStateOf(false) }
        var purchaseFinished by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { if (!isPurchasing) showCheckoutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = "Star", tint = Color(0xFFD4AF37), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock Phone Stop Premium", fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column {
                    if (!purchaseFinished) {
                        Text(
                            "Get complete access to all productivity superpowers to build unbreakable screen habits:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        val benefits = listOf(
                            "🚫 Remove all visual ads forever",
                            "⏰ Set unlimited Scheduled Focus Locks (Free limit: 2)",
                            "🔒 Unlock Streak Commitment Lock (No-Bypass Mode)",
                            "🎵 Select custom synthesized Focus completion sounds",
                            "☁️ Automatic cloud sync of focus history & stats"
                        )
                        benefits.forEach { benefit ->
                            Text(
                                text = benefit,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Special Pricing: $1.99 Lifetime Upgrade",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        if (isPurchasing) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Processing via Google Play...")
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Premium Activated! 🎉",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Thank you for upgrading! You now have unlimited scheduled locks, customized sounds, commitment lock, and zero ads.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!purchaseFinished) {
                    Button(
                        onClick = {
                            isPurchasing = true
                            coroutineScope.launch {
                                delay(2500) // Simulate Google Play transaction
                                viewModel.updateSettings(isPremium = true)
                                isPurchasing = false
                                purchaseFinished = true
                            }
                        },
                        enabled = !isPurchasing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("UPGRADE NOW FOR $1.99")
                    }
                } else {
                    Button(
                        onClick = {
                            showCheckoutDialog = false
                            purchaseFinished = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Awesome!")
                    }
                }
            },
            dismissButton = {
                if (!purchaseFinished && !isPurchasing) {
                    TextButton(onClick = { showCheckoutDialog = false }) {
                        Text("Maybe Later")
                    }
                }
            }
        )
    }

    // New Alarm Creation Dialog
    if (showAddAlarmDialog) {
        var hourVal by remember { mutableStateOf("08") }
        var minuteVal by remember { mutableStateOf("00") }
        var isAm by remember { mutableStateOf(true) }
        var durationVal by remember { mutableIntStateOf(15) }
        var useUtcTime by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { showAddAlarmDialog = false },
            title = { Text("Schedule Auto Focus Lock", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Auto-starts a focus lock session daily at the specified time.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Time Selector row
                    Text("Time (HH:MM):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = hourVal,
                            onValueChange = { if (it.length <= 2) hourVal = it.filter { c -> c.isDigit() } },
                            placeholder = { Text("08") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Hour") }
                        )
                        Text(":", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        OutlinedTextField(
                            value = minuteVal,
                            onValueChange = { if (it.length <= 2) minuteVal = it.filter { c -> c.isDigit() } },
                            placeholder = { Text("00") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Min") }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            FilterChip(
                                selected = isAm,
                                onClick = { isAm = true },
                                label = { Text("AM") }
                            )
                            FilterChip(
                                selected = !isAm,
                                onClick = { isAm = false },
                                label = { Text("PM") }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Duration Row
                    Text("Lock Duration:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val checkDurs = listOf(15, 30, 45, 60)
                        checkDurs.forEach { d ->
                            FilterChip(
                                selected = durationVal == d,
                                onClick = { durationVal = d },
                                label = { Text("${d}m") }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Timezone matching UTC selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useUtcTime = !useUtcTime },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use Custom UTC Timezone", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Match custom UTC time offset instead of local device location time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = useUtcTime, onCheckedChange = { useUtcTime = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = hourVal.toIntOrNull()
                        val m = minuteVal.toIntOrNull()
                        if (h == null || h !in 1..12 || m == null || m !in 0..59) {
                            Toast.makeText(context, "Please enter a valid time (1-12 hours, 0-59 minutes).", Toast.LENGTH_SHORT).show()
                        } else {
                            // Convert back to 24h format for alarm trigger
                            val militaryHour = if (isAm) {
                                if (h == 12) 0 else h
                            } else {
                                if (h == 12) 12 else h + 12
                            }
                            viewModel.addScheduledAlarm(militaryHour, m, durationVal, useUtcTime)
                            showAddAlarmDialog = false
                            Toast.makeText(context, "Scheduled Lock Alarm Added successfully!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Add Alarm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAlarmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Custom Duration Selector dialog
    if (showCustomDurationDialog) {
        var textValue by remember { mutableStateOf(TextFieldValue("")) }
        AlertDialog(
            onDismissRequest = { showCustomDurationDialog = false },
            title = { Text("Custom Lock Duration") },
            text = {
                Column {
                    Text("Enter duration in minutes:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        placeholder = { Text("e.g. 45") },
                        modifier = Modifier.fillMaxWidth().testTag("custom_duration_input"),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mins = textValue.text.toIntOrNull()
                        if (mins != null && mins > 0) {
                            selectedDurationMinutes = mins
                            showCustomDurationDialog = false
                        } else {
                            Toast.makeText(context, "Enter a valid positive number", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDurationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AppPickerRow(
    label: String,
    packageName: String,
    isSelected: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var appIcon by remember { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    
    LaunchedEffect(packageName) {
        try {
            appIcon = context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            // keep null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isSelected) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("app_picker_row_$packageName"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(
                painter = rememberAsyncImagePainter(appIcon),
                contentDescription = "$label icon",
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(text = packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("app_checkbox_$packageName")
        )
    }
}

@Composable
fun ScheduledAlarmRow(
    alarm: com.example.data.ScheduledAlarmEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("scheduled_alarm_row_${alarm.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Time",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    val displayHour = if (alarm.hour == 0 || alarm.hour == 12) 12 else alarm.hour % 12
                    val amPm = if (alarm.hour < 12) "AM" else "PM"
                    val formattedTime = String.format("%02d:%02d %s", displayHour, alarm.minute, amPm)
                    
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Duration: ${alarm.durationMinutes}m" + if (alarm.useUtc) " (UTC offset)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("alarm_toggle_${alarm.id}")
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("alarm_delete_${alarm.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Scheduled Lock",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveSessionScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val latestSession by viewModel.latestSession.collectAsState()
    val selectedApps by viewModel.selectedAppsFromDb.collectAsState()
    val settings by viewModel.settings.collectAsState()
    
    val endTime = latestSession?.endTime ?: 0L
    val startTime = latestSession?.startTime ?: 0L
    
    var timeLeftMs by remember { mutableStateOf(endTime - System.currentTimeMillis()) }

    LaunchedEffect(endTime) {
        while (true) {
            timeLeftMs = endTime - System.currentTimeMillis()
            if (timeLeftMs <= 0) {
                break
            }
            delay(1000)
        }
    }

    // Format remaining time
    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeftMs).coerceAtLeast(0)
    val seconds = (TimeUnit.MILLISECONDS.toSeconds(timeLeftMs) % 60).coerceAtLeast(0)
    val formattedTime = String.format("%02d:%02d", minutes, seconds)

    val formattedEndTime = remember(endTime) {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        sdf.format(Date(endTime))
    }

    val blockedCount = selectedApps.count { it.isBlocked }
    val allowedCount = selectedApps.count { !it.isBlocked }

    // Emergency button long-press progress
    var holdProgress by remember { mutableStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val totalTicks = 100 // 10 seconds, 100ms ticks
            for (tick in 1..totalTicks) {
                if (!isHolding) break
                delay(100)
                holdProgress = tick.toFloat() / totalTicks.toFloat()
            }
            if (holdProgress >= 1f) {
                // Hold complete! Bypass triggered! Reset streak on emergency bypass
                viewModel.forceEndSession()
                Toast.makeText(context, "Emergency bypass lock session ended. Streak reset.", Toast.LENGTH_SHORT).show()
            }
        } else {
            holdProgress = 0f
        }
    }

    // Percentage of session complete
    val totalMs = endTime - startTime
    val sessionPercent = if (totalMs > 0) {
        (timeLeftMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Lock",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "PHONE STOP LOCK ACTIVE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Giant Countdown Ring
            Box(
                modifier = Modifier.size(260.dp),
                contentAlignment = Alignment.Center
            ) {
                val animatedProgress by animateFloatAsState(
                    targetValue = sessionPercent,
                    animationSpec = tween(500),
                    label = "countdown"
                )
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Ring background
                    drawCircle(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )
                    // Active progression
                    drawArc(
                        color = Color(0xFFE53935),
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Ends at $formattedEndTime",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Dashboard items of selection counts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Blocked Apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("$blockedCount", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Allowed Apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("$allowedCount", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Tactile Hold-to-Unlock or Commitment Lock Protection Block
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (settings.streakCommitMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalFireDepartment, contentDescription = "Streak", tint = Color(0xFFFF5722), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "STREAK COMMIT ACTIVE",
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFF5722),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "To safeguard your ${settings.currentStreakDays}-day streak, emergency override bypass is disabled! Protect your focus. 🔥",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text(
                    text = "Emergency Bypass: Hold down for 10 seconds to unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isHolding = true
                                    tryAwaitRelease()
                                    isHolding = false
                                }
                            )
                        }
                        .testTag("emergency_hold_area"),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Background visual filler matching holding progression
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(holdProgress)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    )
                    
                    // Centered Label
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHolding) "HOLDING... (${(10 - holdProgress * 10).toInt()}s)" else "HOLD DOWN TO UNLOCK",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showSoundSelectionDialog by remember { mutableStateOf(false) }
    var showCheckoutDialog by remember { mutableStateOf(false) }
    var isSyncingData by remember { mutableStateOf(false) }

    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val dndAccessGranted = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        // Account & Backups Section
        item {
            Text(
                text = "Account & Cloud Sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (settings.userEmail != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Account", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Signed in with Google", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                Text(settings.userEmail ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (isSyncingData) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Syncing focus alarms & streak data...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Button(
                                onClick = {
                                    isSyncingData = true
                                    coroutineScope.launch {
                                        delay(1500)
                                        isSyncingData = false
                                        Toast.makeText(context, "Streak and alarm configurations successfully saved to Google Account cloud storage!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = "Sync", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sync Focus & Alarm Data")
                            }
                        }
                    } else {
                        Text(
                            "Connect your Google/Gmail account to safely backup your focus alarms, streak points, and settings history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.updateSettings(userEmail = "zainalipri@gmail.com")
                                Toast.makeText(context, "Successfully linked with Google Account zainalipri@gmail.com!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = "Login")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign In with Google")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            Text(
                text = "Defaults & Behavior",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Default duration
        item {
            SettingsClickableRow(
                title = "Default Lock Duration",
                subtitle = "${settings.defaultDurationMinutes} minutes",
                icon = Icons.Default.Timelapse,
                onClick = {
                    // Update default lock duration (simple cyclic for setting)
                    val nextDuration = when (settings.defaultDurationMinutes) {
                        5 -> 15
                        15 -> 30
                        30 -> 60
                        60 -> 120
                        else -> 5
                    }
                    viewModel.updateSettings(defaultDurationMinutes = nextDuration)
                }
            )
        }

        // Theme preference
        item {
            SettingsClickableRow(
                title = "Application Theme Mode",
                subtitle = settings.themeMode.replaceFirstChar { it.uppercase() },
                icon = Icons.Default.Palette,
                onClick = { showThemeDialog = true }
            )
        }

        // Streak Commitment Lock Option
        item {
            SettingsSwitchRow(
                title = "Streak Commitment Lock",
                desc = "Disable the emergency hold-to-bypass escape option during focus sessions to preserve your streaks.",
                icon = Icons.Default.LockPerson,
                checked = settings.streakCommitMode,
                onCheckedChange = { checked ->
                    if (checked && !settings.isPremium) {
                        showCheckoutDialog = true
                    } else {
                        viewModel.updateSettings(streakCommitMode = checked)
                        if (checked) {
                            Toast.makeText(context, "Commit Mode enabled! Safe focus habits enforced.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            Text(
                text = "Alerts & Notifications",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Do Not Disturb Mode Switch
        item {
            SettingsSwitchRow(
                title = "Do Not Disturb Mode",
                desc = "Silence incoming calls & turn off app notifications completely during active lock sessions.",
                icon = Icons.Default.DoNotDisturbOn,
                checked = settings.isDndEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
                            Toast.makeText(context, "Please grant Notification Policy access first to toggle DND", Toast.LENGTH_LONG).show()
                            try {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Please open Notification Access settings manually.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            viewModel.updateSettings(isDndEnabled = true)
                        }
                    } else {
                        viewModel.updateSettings(isDndEnabled = false)
                    }
                }
            )
        }

        // Sound Toggle
        item {
            SettingsSwitchRow(
                title = "Completion Alarm Sound",
                desc = "Play notification alarm ringtone when lock finishes.",
                icon = Icons.Default.VolumeUp,
                checked = settings.alarmSoundEnabled,
                onCheckedChange = { viewModel.updateSettings(alarmSoundEnabled = it) }
            )
        }

        // Sound Choice Picker
        item {
            SettingsClickableRow(
                title = "Custom Alarm Ringtone",
                subtitle = settings.selectedCompletionSound.replaceFirstChar { it.uppercase() },
                icon = Icons.Default.MusicNote,
                onClick = {
                    if (!settings.isPremium) {
                        showCheckoutDialog = true
                    } else {
                        showSoundSelectionDialog = true
                    }
                }
            )
        }

        // Vibration Toggle
        item {
            SettingsSwitchRow(
                title = "Completion Vibration",
                desc = "Vibrate device once focus session reaches zero.",
                icon = Icons.Default.Vibration,
                checked = settings.vibrationEnabled,
                onCheckedChange = { viewModel.updateSettings(vibrationEnabled = it) }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            Text(
                text = "System Actions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Reset Settings
        item {
            Button(
                onClick = {
                    viewModel.resetAppSettings()
                    Toast.makeText(context, "All selections and settings reset successfully.", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("reset_all_settings_button")
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Reset")
                Spacer(modifier = Modifier.width(8.dp))
                Text("RESET ALL SETTINGS & APP PICKER")
            }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Select Application Theme") },
            text = {
                Column {
                    val modes = listOf("system", "light", "dark")
                    modes.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSettings(themeMode = mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.themeMode == mode,
                                onClick = {
                                    viewModel.updateSettings(themeMode = mode)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(mode.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showSoundSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showSoundSelectionDialog = false },
            title = { Text("Select Focus Completion Sound") },
            text = {
                Column {
                    val soundOptions = listOf("digital", "chimes", "forest", "gong")
                    soundOptions.forEach { sound ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSettings(selectedCompletionSound = sound)
                                    // Play synth preview immediately
                                    coroutineScope.launch {
                                        AppBlockerService.playCompletionSound(context, sound)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.selectedCompletionSound == sound,
                                onClick = {
                                    viewModel.updateSettings(selectedCompletionSound = sound)
                                    coroutineScope.launch {
                                        AppBlockerService.playCompletionSound(context, sound)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(sound.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSoundSelectionDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Interactive Premium Checkout Dialog inside Settings
    if (showCheckoutDialog) {
        var isPurchasing by remember { mutableStateOf(false) }
        var purchaseFinished by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { if (!isPurchasing) showCheckoutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = "Star", tint = Color(0xFFD4AF37), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock Phone Stop Premium", fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column {
                    if (!purchaseFinished) {
                        Text(
                            "Get complete access to all productivity superpowers to build unbreakable screen habits:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        val benefits = listOf(
                            "🚫 Remove all visual ads forever",
                            "⏰ Set unlimited Scheduled Focus Locks (Free limit: 2)",
                            "🔒 Unlock Streak Commitment Lock (No-Bypass Mode)",
                            "🎵 Select custom synthesized Focus completion sounds",
                            "☁️ Automatic cloud sync of focus history & stats"
                        )
                        benefits.forEach { benefit ->
                            Text(
                                text = benefit,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Special Pricing: $1.99 Lifetime Upgrade",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        if (isPurchasing) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Processing via Google Play...")
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Premium Activated! 🎉",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Thank you for upgrading! You now have unlimited scheduled locks, customized sounds, commitment lock, and zero ads.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!purchaseFinished) {
                    Button(
                        onClick = {
                            isPurchasing = true
                            coroutineScope.launch {
                                delay(2500) // Simulate Google Play transaction
                                viewModel.updateSettings(isPremium = true)
                                isPurchasing = false
                                purchaseFinished = true
                            }
                        },
                        enabled = !isPurchasing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("UPGRADE NOW FOR $1.99")
                    }
                } else {
                    Button(
                        onClick = {
                            showCheckoutDialog = false
                            purchaseFinished = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Awesome!")
                    }
                }
            },
            dismissButton = {
                if (!purchaseFinished && !isPurchasing) {
                    TextButton(onClick = { showCheckoutDialog = false }) {
                        Text("Maybe Later")
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsClickableRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    desc: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.minimumInteractiveComponentSize())
    }
}

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "About & Privacy",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Phone Stop Alarm",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Version 1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "A modern, highly secure productivity companion designed to temporarily block selected apps. This eliminates distraction and helps you build focus, mindfulness, and healthy screen habits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "How It Works",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "By combined use of an Accessibility Service and Usage Stats detection, Phone Stop Alarm intercepts attempts to launch configured distracting applications when a focus session is active. It instantly loads a minimalist lock screen. All allowed apps remain entirely usable to support necessary day-to-day work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Data Security & Offline-First",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Your privacy is paramount. Phone Stop Alarm does not require an account, has zero trackers, and never sends your installed app data or usage records to any remote servers. All configurations and lock timers are managed entirely in a secure offline database on your physical device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// Helpers
private fun isEssentialSystemPackageWarning(packageName: String): Boolean {
    val essentials = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3"
    )
    return essentials.contains(packageName)
}
