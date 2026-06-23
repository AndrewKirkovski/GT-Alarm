package com.kirkouski.gtwake.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kirkouski.gtwake.companion.data.AlarmRepository
import com.kirkouski.gtwake.companion.data.OnboardingState
import com.kirkouski.gtwake.companion.ring.AlarmRingService
import com.kirkouski.gtwake.companion.ui.edit.AlarmEditScreen
import com.kirkouski.gtwake.companion.ui.edit.AlarmMode
import com.kirkouski.gtwake.companion.ui.help.HelpScreen
import com.kirkouski.gtwake.companion.ui.help.PermissionAudit
import com.kirkouski.gtwake.companion.ui.onboarding.PrivacyConsentDialog
import com.kirkouski.gtwake.companion.ui.list.AlarmListScreen
import com.kirkouski.gtwake.companion.ui.nav.AppBottomBar
import com.kirkouski.gtwake.companion.ui.nav.BOTTOM_BAR_ROUTES
import com.kirkouski.gtwake.companion.ui.nav.Routes
import com.kirkouski.gtwake.companion.ui.nav.switchTab
import com.kirkouski.gtwake.companion.ui.settings.SettingsScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import com.kirkouski.gtwake.companion.ui.theme.GtAlarmTheme
import com.kirkouski.gtwake.companion.ui.theme.gtBackgroundBrush
import com.kirkouski.gtwake.companion.wear.WearBridgeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Wear Engine permission is NOT bootstrapped on launch. Huawei's official
    // sample defers requestPermission(...) to a user button click; doing it on
    // every cold launch (a) hijacks first-launch UX with a Huawei Health popup
    // before the user understands what the app does, and (b) re-prompts users
    // who previously denied. The "Pair watch" button in HelpScreen is the
    // user-driven entry point — wired below into the HelpScreen composable.
    @Inject lateinit var wearBridge: WearBridgeService
    @Inject lateinit var alarmRepository: AlarmRepository
    @Inject lateinit var onboardingState: OnboardingState

    // Wired in onStart — see GtAlarmApp direct-boot guard.
    @Inject lateinit var incomingHandler: com.kirkouski.gtwake.companion.data.sync.IncomingMessageHandler

    // Tracks the user's POST_NOTIFICATIONS choice. We don't gate any
    // functionality on it (alarms still ring without notifications via
    // the AlarmActivity full-screen path), but logging the denial makes
    // a "my alarms don't show in the notification shade" support thread
    // resolvable from logcat alone.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "POST_NOTIFICATIONS granted")
            } else {
                Log.w(
                    TAG,
                    "POST_NOTIFICATIONS denied — alarms will still ring via " +
                        "AlarmActivity but no heads-up / shade entry will appear",
                )
            }
        }

    // reason: onCreate hosts the entire NavHost composable tree because we
    // don't have a separate AppNavigation file yet; each composable route's
    // wiring (onBack / onOpen* lambdas) is declared inline. Splitting into
    // a navHost-building helper would add a file without changing logic —
    // adding the Setup route nudged this 1 line past 60.
    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        ensureFullScreenIntentPermission()

        val startAtEdit = intent.getStringExtra(EXTRA_DEEP_LINK_SCREEN) == SCREEN_ADD
        Log.i(TAG, "onCreate deepLink=${intent.getStringExtra(EXTRA_DEEP_LINK_SCREEN)} startAtEdit=$startAtEdit")

        setContent {
            GtAlarmTheme {
                val brush = gtBackgroundBrush()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush),
                ) {
                val navController = rememberNavController()
                val currentRoute by navController.currentBackStackEntryAsState()
                LaunchedEffect(startAtEdit) {
                    if (startAtEdit) navController.navigate(Routes.edit(null))
                }
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        val route = currentRoute?.destination?.route
                        if (route in BOTTOM_BAR_ROUTES) {
                            AppBottomBar(
                                currentRoute = route,
                                onSelect = { navController.switchTab(it) },
                            )
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LIST,
                        modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
                    ) {
                        composable(Routes.LIST) {
                            AlarmListScreen(
                                onAdd = { navController.navigate(Routes.edit(null)) },
                                onEdit = { id -> navController.navigate(Routes.edit(id)) },
                                onOpenExactAlarmSettings = { openExactAlarmSettings() },
                                onOpenBatteryOptSettings = { openBatteryOptSettings() },
                                onOpenHelp = { navController.switchTab(Routes.HELP) },
                                onAuthorizeWatch = {
                                    wearBridge.requestPermissionFromActivity(this@MainActivity)
                                },
                            )
                        }
                        composable(Routes.HELP) {
                            HelpScreen(
                                onFireAlarmNow = { fireAlarmNow() },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen()
                        }
                        composable(
                            route = Routes.EDIT_WITH_ARG,
                            arguments = listOf(
                                navArgument(Routes.EDIT_ARG_ID) {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                                navArgument(Routes.EDIT_ARG_MODE) {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = AlarmMode.ABSOLUTE.name
                                },
                            ),
                        ) { entry ->
                            val idArg = entry.arguments?.getString(Routes.EDIT_ARG_ID)?.toLongOrNull()
                            val modeArg = entry.arguments?.getString(Routes.EDIT_ARG_MODE)
                            val initialMode = runCatching {
                                AlarmMode.valueOf(modeArg.orEmpty())
                            }.getOrDefault(AlarmMode.ABSOLUTE)
                            AlarmEditScreen(
                                alarmId = idArg,
                                initialMode = initialMode,
                                onDone = { navController.popBackStack() },
                            )
                        }
                    }
                }
                // First-launch privacy consent (AppGallery rule 7.5). Blocks
                // the app behind a non-dismissable dialog until the user agrees.
                var showPrivacyConsent by remember {
                    mutableStateOf(!onboardingState.privacyPolicyAccepted())
                }
                if (showPrivacyConsent) {
                    PrivacyConsentDialog(
                        onAgree = {
                            onboardingState.markPrivacyPolicyAccepted()
                            showPrivacyConsent = false
                        },
                        onDisagree = { finishAndRemoveTask() },
                        onReadPolicy = { openPrivacyPolicy() },
                    )
                }
                } // Box
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Idempotent — covers the pre-unlock-deferred wire from GtAlarmApp.
        wearBridge.setIncomingHandler(incomingHandler)
        // Foreground-driven phone→watch reconcile (watch can't wake the phone).
        alarmRepository.scheduleWatchSync()
        // Seed watch-connection status (one-shot local query) + start the
        // event-driven monitor for the UI's lifetime. Tied to onStart/onStop so
        // it's never a background service. Drives statusFlow for every UI.
        wearBridge.startConnectionMonitor()
    }

    override fun onStop() {
        super.onStop()
        // Stop the connection monitor when the UI leaves the foreground — no
        // standing background service.
        wearBridge.stopConnectionMonitor()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // First-run one-shot: open the system's full-screen-intent settings the
    // first time we detect the AppOps mode is not MODE_ALLOWED. On Samsung
    // One UI 6 the appop defaults to MODE_DEFAULT — NotificationManager
    // .canUseFullScreenIntent() returns true for that mode but Samsung's
    // vendor FSI-delivery layer still silently drops the op at fire time,
    // so we MUST query AppOps directly (see PermissionAudit docs). After
    // the one-shot fires, subsequent launches rely on the Setup banner in
    // the alarm list — auto-launching every cold start would yank the user
    // out of the app on devices where the default mode persists.
    private fun ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (onboardingState.fsiPromptShown()) return
        if (PermissionAudit.checkFullScreenIntent(this) == PermissionAudit.Status.GRANTED) return
        onboardingState.markFsiPromptShown()
        Log.i(TAG, "first-run FSI prompt — appop not MODE_ALLOWED, deeplinking to Settings")
        openFullScreenIntentSettings()
    }

    private fun openExactAlarmSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "ACTION_REQUEST_SCHEDULE_EXACT_ALARM unavailable: ${it.message}") }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT unavailable: ${it.message}") }
    }

    // Fires the alarm-ring path immediately via the EXACT same call
    // AlarmBroadcastReceiver makes when AlarmManager dispatches a scheduled
    // alarm. No bypass of AlarmRingService — phone + watch ring paths
    // both flow through AlarmRingService.handleRing → wearBridge.sendAlarmFired.
    private fun fireAlarmNow() {
        Log.i(TAG, "fire alarm now requested")
        lifecycleScope.launch {
            runCatching {
                val alarmId = alarmRepository.ensureDebugAlarmId()
                val intent = Intent(this@MainActivity, AlarmRingService::class.java).apply {
                    action = AlarmRingService.ACTION_RING
                    putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
                }
                startForegroundService(intent)
                alarmId
            }
                .onSuccess { alarmId ->
                    Log.i(TAG, "fire-now dispatched id=$alarmId")
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.help_debug_fire_now_toast, alarmId),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { e ->
                    Log.w(TAG, "fire-now failed: ${e.message}", e)
                }
        }
    }

    private fun openBatteryOptSettings() {
        // Direct request opens a system-level allow/deny dialog. If the OEM
        // doesn't surface that dialog, fall back to the global "Battery
        // optimization" settings list so the user can find the app.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val direct1 = runCatching { startActivity(direct) }
        direct1.onFailure {
            Log.w(TAG, "battery opt direct unavailable: ${it.message}")
        }
        if (direct1.isFailure) {
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(list) }
                .onFailure {
                    Log.w(TAG, "battery opt list fallback unavailable: ${it.message}")
                }
        }
    }

    private fun openPrivacyPolicy() {
        val intent = Intent(Intent.ACTION_VIEW, getString(R.string.help_privacy_url).toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "open privacy policy failed: ${it.message}") }
    }

    companion object {
        const val EXTRA_DEEP_LINK_SCREEN = "screen"
        const val SCREEN_ADD = "add"
        private const val TAG = "MainActivity"
    }
}
