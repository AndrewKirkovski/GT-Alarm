// reason: HelpScreen is the central UI for both reliability setup, voice
// help, and the donate / device-unsupported / credits surfaces. ~18
// top-level helpers = the screen itself + ~8 small composables (status
// dot, permission row, status banner, action card, debug card, brand tips
// card, section header, credits footer) + 4 brand-to-resource lookups +
// 3 deep-link helpers (openDefaultAppsSettings, openExternalUrl,
// startActivitySafely) + hasUnresolvedSetup. Splitting into multiple
// files would hide the linear dependency on PermissionAudit + DeviceBrand
// enums for no reuse — every helper is referenced exactly once, from
// inside the screen.
@file:Suppress("TooManyFunctions")

package com.kirkouski.gtalarm.ui.help

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kirkouski.gtalarm.R
import com.kirkouski.gtalarm.voice.DefaultAlarmDetector
import com.kirkouski.gtalarm.voice.DeviceBrand
import com.kirkouski.gtalarm.voice.DeviceBrandDetector

/**
 * Unified Help screen. Replaces the previous split between Voice-setup help
 * and the Setup Status screen — both lived under separate top-bar icons and
 * carried overlapping content, which felt like two pages for one concern.
 *
 * Sections in display order:
 *   1. Voice-default status banner (existing DefaultAlarmDetector readout).
 *   2. Reliability checklist — programmatic permission audit, each row with
 *      a Fix button that deep-links to the matching Settings page.
 *   3. Phone-brand tips — dropdown defaults to detected brand, two subsections
 *      ("Keep awake" + "Voice") per brand.
 *   4. Pair watch card.
 *   5. Debug card (test alarm + fire-now).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// reason: HelpScreen is a single linear Scaffold + Column of fixed cards.
// Each subsection (StatusBanner, ReliabilityCard, BrandCard, etc.) is already
// factored out. The remaining top-level layout is referenced exactly once
// and splitting it would just push state-routing across more files.
@Suppress("LongMethod")
fun HelpScreen(
    onBack: () -> Unit,
    onPairWatch: () -> Unit,
    onScheduleTestAlarm: () -> Unit,
    onFireAlarmNow: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var voiceStatus by remember { mutableStateOf(DefaultAlarmDetector.detect(context)) }
    var permissionChecks by remember { mutableStateOf(PermissionAudit.audit(context)) }
    DisposableEffect(lifecycleOwner) {
        // Refresh both audits on resume so flips made in system Settings show
        // up the moment the user returns from a Fix deep-link.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                voiceStatus = DefaultAlarmDetector.detect(context)
                permissionChecks = PermissionAudit.audit(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            // ----- Reliability section -----
            SectionHeader(
                titleRes = R.string.help_section_reliability,
                bodyRes = R.string.help_section_reliability_intro,
            )
            permissionChecks.forEach { check ->
                PermissionRow(
                    check = check,
                    onFix = {
                        val intent = PermissionAudit.intentFor(context, check.item)
                        startActivitySafely(context, intent)
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ----- Brand tips section -----
            SectionHeader(
                titleRes = R.string.help_section_brand_tips,
                bodyRes = R.string.help_section_brand_tips_intro,
            )
            BrandTipsCard()

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ----- Voice-default status + intro (existing concern) -----
            VoiceStatusBanner(voiceStatus)
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
            Text(
                text = stringResource(R.string.help_reset_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ----- Pair watch card -----
            ActionCard(
                titleRes = R.string.help_pair_watch_title,
                bodyRes = R.string.help_pair_watch_body,
                buttonLabelRes = R.string.help_pair_watch_button,
                onClick = onPairWatch,
            )

            // ----- Device-not-supported / contact dev (problem-first) -----
            val contactUrl = stringResource(R.string.help_device_unsupported_url)
            ActionCard(
                titleRes = R.string.help_device_unsupported_title,
                bodyRes = R.string.help_device_unsupported_body,
                buttonLabelRes = R.string.help_device_unsupported_button,
                onClick = { openExternalUrl(context, contactUrl) },
            )

            // ----- Support development (ko-fi donate) -----
            val donateUrl = stringResource(R.string.help_donate_url)
            ActionCard(
                titleRes = R.string.help_donate_title,
                bodyRes = R.string.help_donate_body,
                buttonLabelRes = R.string.help_donate_button,
                onClick = { openExternalUrl(context, donateUrl) },
            )

            // ----- Debug card (English-only / translatable=false) -----
            DebugCard(onScheduleTestAlarm, onFireAlarmNow)

            // ----- Credits / attribution footer -----
            CreditsFooter()
        }
    }
}

@Composable
private fun CreditsFooter() {
    val context = LocalContext.current
    val url = stringResource(R.string.help_credits_flaticon_url)
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.help_credits_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.help_credits_icons),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { openExternalUrl(context, url) },
        )
    }
}

@Composable
private fun SectionHeader(titleRes: Int, bodyRes: Int) {
    Column {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PermissionRow(
    check: PermissionAudit.Check,
    onFix: () -> Unit,
) {
    val granted = check.status == PermissionAudit.Status.GRANTED
    val skipped = check.status == PermissionAudit.Status.NOT_APPLICABLE
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(granted = granted, skipped = skipped)
                Text(
                    text = stringResource(check.item.titleRes()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                text = stringResource(check.item.bodyRes()),
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
    when {
        skipped -> Icon(
            imageVector = Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp),
        )
        granted -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(24.dp),
        )
        else -> Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandTipsCard() {
    val detected = remember { DeviceBrandDetector.current() }
    var selected by remember { mutableStateOf(detected) }
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(selected.labelRes()),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

            // Keep-awake subsection
            Text(
                text = stringResource(R.string.help_subsection_keep_awake),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(selected.keepAwakeTipsRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Voice subsection
            Text(
                text = stringResource(R.string.help_subsection_voice),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(selected.voiceTipsRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun VoiceStatusBanner(status: DefaultAlarmDetector.DefaultStatus) {
    // reason: rememberVectorPainter wraps a Material ImageVector so the Material
    // success state shares the same Painter type as the colored PNG drawables.
    // Lets BannerSpec carry one icon type for the whole when{}.
    val checkCirclePainter = rememberVectorPainter(Icons.Default.CheckCircle)
    val infoPainter = painterResource(R.drawable.ic_info)
    val warningPainter = painterResource(R.drawable.ic_warning)
    val spec = when (status) {
        is DefaultAlarmDetector.DefaultStatus.WeAreDefault ->
            BannerSpec(checkCirclePainter, Color(0xFF2E7D32), R.string.help_status_default, null)
        is DefaultAlarmDetector.DefaultStatus.Disambiguator ->
            BannerSpec(infoPainter, Color.Unspecified, R.string.help_status_disambiguator, null)
        is DefaultAlarmDetector.DefaultStatus.OtherIsDefault ->
            BannerSpec(infoPainter, Color.Unspecified, R.string.help_status_other, status.displayName)
        is DefaultAlarmDetector.DefaultStatus.NoHandlers ->
            BannerSpec(warningPainter, Color.Unspecified, R.string.help_status_none, null)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = spec.icon,
                contentDescription = null,
                tint = spec.tint,
                modifier = Modifier.size(28.dp),
            )
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
    val icon: Painter,
    val tint: Color,
    val textRes: Int,
    val arg: String?,
)

@Composable
private fun ActionCard(
    titleRes: Int,
    bodyRes: Int,
    buttonLabelRes: Int,
    onClick: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(buttonLabelRes))
            }
        }
    }
}

@Composable
private fun DebugCard(
    onScheduleTestAlarm: () -> Unit,
    onFireAlarmNow: () -> Unit,
) {
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

private fun openDefaultAppsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { primary ->
            android.util.Log.w(TAG, "MANAGE_DEFAULT_APPS_SETTINGS unavailable: ${primary.message}")
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
                .onFailure { secondary ->
                    android.util.Log.w(TAG, "ACTION_SETTINGS fallback also failed: ${secondary.message}")
                }
        }
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { android.util.Log.w(TAG, "openExternalUrl failed for $url: ${it.message}") }
}

private fun startActivitySafely(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { primary ->
            // OEM customization sometimes removes specific Settings actions.
            // Fall back to the per-app details page, which always exists.
            android.util.Log.w(TAG, "Settings deep-link ${intent.action} unavailable: ${primary.message}")
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
                .onFailure { secondary ->
                    android.util.Log.w(TAG, "APPLICATION_DETAILS_SETTINGS fallback also failed: ${secondary.message}")
                }
        }
}

private const val TAG = "HelpScreen"

// ---- Brand <-> resource lookup helpers ----

private fun DeviceBrand.labelRes(): Int = when (this) {
    DeviceBrand.PIXEL -> R.string.setup_oem_label_pixel
    DeviceBrand.SAMSUNG -> R.string.setup_oem_label_samsung
    DeviceBrand.XIAOMI -> R.string.setup_oem_label_xiaomi
    DeviceBrand.ONEPLUS -> R.string.setup_oem_label_oneplus
    DeviceBrand.HUAWEI -> R.string.setup_oem_label_huawei
    DeviceBrand.OTHER -> R.string.setup_oem_label_other
}

private fun DeviceBrand.keepAwakeTipsRes(): Int = when (this) {
    DeviceBrand.PIXEL -> R.string.setup_oem_keep_pixel
    DeviceBrand.SAMSUNG -> R.string.setup_oem_keep_samsung
    DeviceBrand.XIAOMI -> R.string.setup_oem_keep_xiaomi
    DeviceBrand.ONEPLUS -> R.string.setup_oem_keep_oneplus
    DeviceBrand.HUAWEI -> R.string.setup_oem_keep_huawei
    DeviceBrand.OTHER -> R.string.setup_oem_keep_other
}

private fun DeviceBrand.voiceTipsRes(): Int = when (this) {
    DeviceBrand.PIXEL -> R.string.setup_oem_voice_pixel
    DeviceBrand.SAMSUNG -> R.string.setup_oem_voice_samsung
    DeviceBrand.XIAOMI -> R.string.setup_oem_voice_xiaomi
    DeviceBrand.ONEPLUS -> R.string.setup_oem_voice_oneplus
    DeviceBrand.HUAWEI -> R.string.setup_oem_voice_huawei
    DeviceBrand.OTHER -> R.string.setup_oem_voice_other
}

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

/**
 * Returns true if any must-have permission is denied. Used by the home screen
 * to decide whether to show the "Setup needed" banner. Kept in this file
 * (alongside the audit consumer) instead of in SetupScreen (now deleted).
 */
fun hasUnresolvedSetup(context: Context): Boolean =
    PermissionAudit.audit(context).any { it.status == PermissionAudit.Status.DENIED }
