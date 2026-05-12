package com.kirkouski.gtalarm

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
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kirkouski.gtalarm.data.AlarmRepository
import com.kirkouski.gtalarm.ring.AlarmRingService
import com.kirkouski.gtalarm.ui.edit.AlarmEditScreen
import com.kirkouski.gtalarm.ui.help.HelpScreen
import com.kirkouski.gtalarm.ui.list.AlarmListScreen
import com.kirkouski.gtalarm.ui.nav.Routes
import com.kirkouski.gtalarm.ui.theme.GtAlarmTheme
import com.kirkouski.gtalarm.wear.WearBridgeService
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        ensureFullScreenIntentPermission()

        val startAtEdit = intent.getStringExtra(EXTRA_DEEP_LINK_SCREEN) == SCREEN_ADD
        Log.i(TAG, "onCreate deepLink=${intent.getStringExtra(EXTRA_DEEP_LINK_SCREEN)} startAtEdit=$startAtEdit")

        setContent {
            GtAlarmTheme {
                val navController = rememberNavController()
                LaunchedEffect(startAtEdit) {
                    if (startAtEdit) navController.navigate(Routes.edit(null))
                }
                NavHost(
                    navController = navController,
                    startDestination = Routes.LIST,
                ) {
                    composable(Routes.LIST) {
                        AlarmListScreen(
                            onAdd = { navController.navigate(Routes.edit(null)) },
                            onEdit = { id -> navController.navigate(Routes.edit(id)) },
                            onOpenExactAlarmSettings = { openExactAlarmSettings() },
                            onOpenBatteryOptSettings = { openBatteryOptSettings() },
                            onOpenHelp = { navController.navigate(Routes.HELP) },
                        )
                    }
                    composable(Routes.HELP) {
                        HelpScreen(
                            onBack = { navController.popBackStack() },
                            onPairWatch = {
                                wearBridge.requestPermissionFromActivity(this@MainActivity)
                            },
                            onScheduleTestAlarm = { scheduleTestAlarm() },
                            onFireAlarmNow = { fireAlarmNow() },
                        )
                    }
                    composable(
                        route = Routes.EDIT_WITH_ARG,
                        arguments = listOf(navArgument(Routes.EDIT_ARG_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }),
                    ) { entry ->
                        val idArg = entry.arguments?.getString(Routes.EDIT_ARG_ID)?.toLongOrNull()
                        AlarmEditScreen(
                            alarmId = idArg,
                            onDone = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
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

    private fun ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (nm?.canUseFullScreenIntent() == false) {
            openFullScreenIntentSettings()
        }
    }

    private fun openExactAlarmSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun scheduleTestAlarm() {
        Log.i(TAG, "test alarm requested")
        lifecycleScope.launch {
            runCatching { alarmRepository.scheduleTestFireInOneMinute() }
                .onSuccess { trigger ->
                    Log.i(TAG, "test alarm scheduled trigger=$trigger")
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.help_debug_test_alarm_toast),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { e ->
                    Log.w(TAG, "test alarm failed: ${e.message}", e)
                }
        }
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
        val started = runCatching { startActivity(direct) }.isSuccess
        if (!started) {
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(list) }
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK_SCREEN = "screen"
        const val SCREEN_ADD = "add"
        private const val TAG = "MainActivity"
    }
}
