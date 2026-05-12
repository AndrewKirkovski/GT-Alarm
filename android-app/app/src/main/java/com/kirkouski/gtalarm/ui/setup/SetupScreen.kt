package com.kirkouski.gtalarm.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.ui.help.PermissionAudit
import com.kirkouski.gtalarm.voice.DeviceBrand
import com.kirkouski.gtalarm.voice.DeviceBrandDetector

/**
 * Reliability setup hub. Three sections:
 *  1. Permission audit — programmatic checks for the 4 toggles that block
 *     lockscreen ring (notifications, exact alarm, full-screen intent,
 *     battery optimization). Each row has a Fix button that deep-links to
 *     the exact Settings page.
 *  2. OEM tips — the system APIs can't probe Samsung's Never-sleeping apps,
 *     MIUI Autostart, etc. A dropdown defaults to the detected manufacturer
 *     and shows only that brand's manual steps.
 *  3. (link to voice-setup help, kept distinct because it's a different
 *     concern — making GT Alarm the Google Assistant target.)
 */
// reason: top-level Compose screens are inherently long — Scaffold + topbar +
// Column of fixed cards (permission rows, OEM tips card, voice-help link).
// Splitting into ScreenBody + Header composables adds wrappers but no reuse.
// Same pattern as AlarmListScreen / AlarmEditScreen / HelpScreen.
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onOpenVoiceHelp: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var checks by remember { mutableStateOf(PermissionAudit.audit(context)) }
    DisposableEffect(lifecycleOwner) {
        // Re-run the audit on ON_RESUME so flips made in system Settings show
        // up immediately when the user returns. Identity check on the list
        // would race; just re-assign unconditionally.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checks = PermissionAudit.audit(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_setup_title)) },
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
            Text(
                text = stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            checks.forEach { check ->
                PermissionRow(
                    check = check,
                    onFix = {
                        val intent = PermissionAudit.intentFor(context, check.item)
                        startActivitySafely(context, intent)
                    },
                )
            }

            BrandTipsCard()

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.setup_voice_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.setup_voice_card_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedButton(
                        onClick = onOpenVoiceHelp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.setup_open_voice_help))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    check: PermissionAudit.Check,
    onFix: () -> Unit,
) {
    val titleRes = check.item.titleRes()
    val bodyRes = check.item.bodyRes()
    val granted = check.status == PermissionAudit.Status.GRANTED
    val skipped = check.status == PermissionAudit.Status.NOT_APPLICABLE

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(granted = granted, skipped = skipped)
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!granted && !skipped) {
                FilledTonalButton(
                    onClick = onFix,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.setup_fix))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(granted: Boolean, skipped: Boolean) {
    val (icon, tint) = when {
        skipped -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
        granted -> Icons.Default.CheckCircle to Color(0xFF2E7D32)
        else -> Icons.Default.Warning to MaterialTheme.colorScheme.error
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandTipsCard() {
    var selected by remember { mutableStateOf(DeviceBrandDetector.current()) }
    var expanded by remember { mutableStateOf(false) }
    val detected = remember { DeviceBrandDetector.current() }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.setup_oem_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.setup_oem_card_intro),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Dropdown trigger. Material3's ExposedDropdownMenuBox would be
            // tidier but pulls in ExposedDropdownMenuDefaults theming we
            // don't otherwise use — an OutlinedButton + DropdownMenu is
            // ~10 lines lighter for the same shape.
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(
                    text = stringResource(selected.labelRes()),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DeviceBrand.entries.forEach { brand ->
                    DropdownMenuItem(
                        text = {
                            val label = if (brand == detected) {
                                stringResource(R.string.setup_oem_label_detected, stringResource(brand.labelRes()))
                            } else {
                                stringResource(brand.labelRes())
                            }
                            Text(label)
                        },
                        onClick = {
                            selected = brand
                            expanded = false
                        },
                    )
                }
            }

            Text(
                text = stringResource(selected.tipsRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

// reason: keeps the @Composable site lean. UI inverse of PermissionAudit.Item.
private fun PermissionAudit.Item.titleRes(): Int = when (this) {
    PermissionAudit.Item.POST_NOTIFICATIONS -> R.string.setup_perm_notifications_title
    PermissionAudit.Item.EXACT_ALARM -> R.string.setup_perm_exact_alarm_title
    PermissionAudit.Item.FULL_SCREEN_INTENT -> R.string.setup_perm_fsi_title
    PermissionAudit.Item.BATTERY_UNRESTRICTED -> R.string.setup_perm_battery_title
}

private fun PermissionAudit.Item.bodyRes(): Int = when (this) {
    PermissionAudit.Item.POST_NOTIFICATIONS -> R.string.setup_perm_notifications_body
    PermissionAudit.Item.EXACT_ALARM -> R.string.setup_perm_exact_alarm_body
    PermissionAudit.Item.FULL_SCREEN_INTENT -> R.string.setup_perm_fsi_body
    PermissionAudit.Item.BATTERY_UNRESTRICTED -> R.string.setup_perm_battery_body
}

private fun DeviceBrand.labelRes(): Int = when (this) {
    DeviceBrand.PIXEL -> R.string.setup_oem_label_pixel
    DeviceBrand.SAMSUNG -> R.string.setup_oem_label_samsung
    DeviceBrand.XIAOMI -> R.string.setup_oem_label_xiaomi
    DeviceBrand.ONEPLUS -> R.string.setup_oem_label_oneplus
    DeviceBrand.HUAWEI -> R.string.setup_oem_label_huawei
    DeviceBrand.OTHER -> R.string.setup_oem_label_other
}

private fun DeviceBrand.tipsRes(): Int = when (this) {
    DeviceBrand.PIXEL -> R.string.setup_oem_tips_pixel
    DeviceBrand.SAMSUNG -> R.string.setup_oem_tips_samsung
    DeviceBrand.XIAOMI -> R.string.setup_oem_tips_xiaomi
    DeviceBrand.ONEPLUS -> R.string.setup_oem_tips_oneplus
    DeviceBrand.HUAWEI -> R.string.setup_oem_tips_huawei
    DeviceBrand.OTHER -> R.string.setup_oem_tips_other
}

private fun startActivitySafely(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure {
            // OEM customization sometimes removes specific Settings actions.
            // Fall back to the per-app details page, which always exists.
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
}

/**
 * Returns true if any must-have permission is denied. Used by the home
 * screen to decide whether to show the "Setup needed" banner.
 */
fun hasUnresolvedSetup(context: Context): Boolean {
    return PermissionAudit.audit(context).any { it.status == PermissionAudit.Status.DENIED }
}
