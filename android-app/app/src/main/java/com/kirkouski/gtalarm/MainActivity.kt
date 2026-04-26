package com.kirkouski.gtalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kirkouski.gtalarm.ui.edit.AlarmEditScreen
import com.kirkouski.gtalarm.ui.list.AlarmListScreen
import com.kirkouski.gtalarm.ui.nav.Routes
import com.kirkouski.gtalarm.ui.theme.GtAlarmTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        ensureFullScreenIntentPermission()

        val startAtEdit = intent.getStringExtra(EXTRA_DEEP_LINK_SCREEN) == SCREEN_ADD

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
    }
}
