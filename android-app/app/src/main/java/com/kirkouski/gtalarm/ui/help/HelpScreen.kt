package com.kirkouski.gtalarm.ui.help

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.voice.DefaultAlarmDetector
import com.kirkouski.gtalarm.voice.DeviceBrand
import com.kirkouski.gtalarm.voice.DeviceBrandDetector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
// reason: this composable is a single linear Scaffold + Column of fixed,
// non-reordered cards. Per-brand cards already factored into BrandCard().
// Splitting the remaining 30-line top-level layout adds a wrapper file
// without enabling reuse — the whole tree is referenced exactly once.
fun HelpScreen(
    onBack: () -> Unit,
    onPairWatch: () -> Unit,
    onScheduleTestAlarm: () -> Unit,
    onFireAlarmNow: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(DefaultAlarmDetector.detect(context)) }
    DisposableEffect(lifecycleOwner) {
        // Refresh on resume so the user sees the new state after coming back
        // from system settings (where they may have changed the default).
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = DefaultAlarmDetector.detect(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val brand = remember { DeviceBrandDetector.current() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_help_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBanner(status)
            Text(
                text = stringResource(R.string.help_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { openDefaultAppsSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.help_open_default_apps))
            }
            BrandCard(
                isCurrent = brand == DeviceBrand.PIXEL,
                titleRes = R.string.help_section_pixel_title,
                bodyRes = R.string.help_section_pixel_body,
            )
            BrandCard(
                isCurrent = brand == DeviceBrand.SAMSUNG,
                titleRes = R.string.help_section_samsung_title,
                bodyRes = R.string.help_section_samsung_body,
            )
            BrandCard(
                isCurrent = brand == DeviceBrand.XIAOMI,
                titleRes = R.string.help_section_xiaomi_title,
                bodyRes = R.string.help_section_xiaomi_body,
            )
            BrandCard(
                isCurrent = brand == DeviceBrand.ONEPLUS,
                titleRes = R.string.help_section_oneplus_title,
                bodyRes = R.string.help_section_oneplus_body,
            )
            BrandCard(
                isCurrent = brand == DeviceBrand.HUAWEI,
                titleRes = R.string.help_section_huawei_title,
                bodyRes = R.string.help_section_huawei_body,
            )
            BrandCard(
                isCurrent = brand == DeviceBrand.OTHER,
                titleRes = R.string.help_section_other_title,
                bodyRes = R.string.help_section_other_body,
            )
            Text(
                text = stringResource(R.string.help_reset_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.help_pair_watch_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.help_pair_watch_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedButton(
                        onClick = onPairWatch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.help_pair_watch_button))
                    }
                }
            }
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.help_debug_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.help_debug_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedButton(
                        onClick = onScheduleTestAlarm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.help_debug_test_alarm_button))
                    }
                    OutlinedButton(
                        onClick = onFireAlarmNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.help_debug_fire_now_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: DefaultAlarmDetector.DefaultStatus) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val spec = when (status) {
        is DefaultAlarmDetector.DefaultStatus.WeAreDefault ->
            BannerSpec(Icons.Default.CheckCircle, Color(0xFF2E7D32), R.string.help_status_default, null)
        is DefaultAlarmDetector.DefaultStatus.Disambiguator ->
            BannerSpec(Icons.Default.Info, primary, R.string.help_status_disambiguator, null)
        is DefaultAlarmDetector.DefaultStatus.OtherIsDefault ->
            BannerSpec(Icons.Default.Info, primary, R.string.help_status_other, status.displayName)
        is DefaultAlarmDetector.DefaultStatus.NoHandlers ->
            BannerSpec(Icons.Default.Warning, error, R.string.help_status_none, null)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(spec.icon, contentDescription = null, tint = spec.tint, modifier = Modifier.size(28.dp))
            Text(
                text = if (spec.arg != null) stringResource(spec.textRes, spec.arg) else stringResource(spec.textRes),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private data class BannerSpec(
    val icon: ImageVector,
    val tint: Color,
    val textRes: Int,
    val arg: String?,
)

@Composable
private fun BrandCard(isCurrent: Boolean, titleRes: Int, bodyRes: Int) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.help_for_your_device),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = stringResource(titleRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (isCurrent) 4.dp else 0.dp),
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun openDefaultAppsSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            // Fall back to general Settings; some OEMs don't ship this action.
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
}
