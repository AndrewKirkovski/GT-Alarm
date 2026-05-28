// reason: HelpScreen is the central UI for both reliability setup, voice
// help, and the donate / device-unsupported / credits surfaces. ~18
// top-level helpers = the screen itself + ~8 small composables (status
// dot, permission row, status banner, action card, debug card, brand tips
// card, section header, credits footer) + 4 brand-to-resource lookups +
// 2 deep-link helpers (openExternalUrl, startActivitySafely) +
// hasUnresolvedSetup. Splitting into multiple
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
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
import com.kirkouski.gtalarm.ui.components.AccordionSection
import com.kirkouski.gtalarm.ui.components.StatusMarkerDot
import com.kirkouski.gtalarm.voice.DefaultAlarmDetector
import com.kirkouski.gtalarm.voice.DeviceBrand
import com.kirkouski.gtalarm.voice.DeviceBrandDetector

/**
 * Unified Help screen. Every section is a collapsible [AccordionSection] so
 * the screen opens compact.
 *
 * Display order: Donate first, then the Reliability checklist (its header
 * carries a green/red [StatusMarkerDot]), then Brand tips, Voice setup, Pair
 * watch, Different-watch, Debug, and a plain Credits footer.
 *
 * Initial expand state is problem-first: if any reliability check is denied
 * the checklist opens; otherwise the Donate section opens.
 */
@Composable
// reason: HelpScreen is a single linear Scaffold + Column of accordion
// sections. Each section body is already factored into its own composable;
// the remaining layout is referenced once and splitting it would just push
// accordion open-state routing across more files.
@Suppress("LongMethod")
fun HelpScreen(
    onPairWatch: () -> Unit,
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

    val hasUnresolved = permissionChecks.any { it.status == PermissionAudit.Status.DENIED }
    var checklistOpen by rememberSaveable { mutableStateOf(hasUnresolved) }
    var brandOpen by rememberSaveable { mutableStateOf(false) }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    var pairOpen by rememberSaveable { mutableStateOf(false) }
    var deviceOpen by rememberSaveable { mutableStateOf(false) }
    var debugOpen by rememberSaveable { mutableStateOf(false) }

    val donateUrl = stringResource(R.string.help_donate_url)
    val contactUrl = stringResource(R.string.help_device_unsupported_url)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val navBarPaddingDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height - navBarPaddingDp.toPx() - 38.dp.toPx(),
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
            // Hero: Ko-fi/donate — always visible, floating on gradient
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.help_donate_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.help_donate_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(
                    onClick = { openExternalUrl(context, donateUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.help_donate_button))
                }
            }

            Spacer(Modifier.height(4.dp))

            // Content card — all other sections grouped with horizontal margins
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    AccordionSection(
                        title = stringResource(R.string.help_section_reliability),
                        expanded = checklistOpen,
                        onToggle = { checklistOpen = !checklistOpen },
                        leading = { StatusMarkerDot(ok = !hasUnresolved) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.help_section_reliability_intro),
                                style = MaterialTheme.typography.bodyMedium,
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
                        }
                    }

                    HorizontalDivider()

                    AccordionSection(
                        title = stringResource(R.string.help_section_brand_tips),
                        expanded = brandOpen,
                        onToggle = { brandOpen = !brandOpen },
                    ) {
                        Text(
                            text = stringResource(R.string.help_section_brand_tips_intro),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        BrandTipsCard()
                    }

                    HorizontalDivider()

                    AccordionSection(
                        title = stringResource(R.string.help_section_voice),
                        expanded = voiceOpen,
                        onToggle = { voiceOpen = !voiceOpen },
                    ) {
                        VoiceStatusBanner(voiceStatus)
                        Text(
                            text = stringResource(R.string.help_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.help_reset_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    HorizontalDivider()

                    AccordionSection(
                        title = stringResource(R.string.help_pair_watch_title),
                        expanded = pairOpen,
                        onToggle = { pairOpen = !pairOpen },
                    ) {
                        AccordionAction(
                            bodyRes = R.string.help_pair_watch_body,
                            buttonLabelRes = R.string.help_pair_watch_button,
                            onClick = onPairWatch,
                        )
                    }

                    HorizontalDivider()

                    AccordionSection(
                        title = stringResource(R.string.help_device_unsupported_title),
                        expanded = deviceOpen,
                        onToggle = { deviceOpen = !deviceOpen },
                    ) {
                        AccordionAction(
                            bodyRes = R.string.help_device_unsupported_body,
                            buttonLabelRes = R.string.help_device_unsupported_button,
                            onClick = { openExternalUrl(context, contactUrl) },
                        )
                    }

                    HorizontalDivider()

                    AccordionSection(
                        title = stringResource(R.string.help_debug_title),
                        expanded = debugOpen,
                        onToggle = { debugOpen = !debugOpen },
                    ) {
                        DebugContent(onFireAlarmNow)
                    }
                }
            }

            CreditsFooter()
            Spacer(
                Modifier.height(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                ),
            )
            }
        }
    }
}

/** Body text + a full-width action button — the shared layout of the
 *  Donate / Pair-watch / Different-watch accordions. */
@Composable
private fun AccordionAction(
    bodyRes: Int,
    buttonLabelRes: Int,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(bodyRes),
        style = MaterialTheme.typography.bodyMedium,
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(stringResource(buttonLabelRes))
    }
}

@Composable
private fun CreditsFooter() {
    val context = LocalContext.current
    val url = stringResource(R.string.help_credits_icons_url)
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
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
private fun PermissionRow(
    check: PermissionAudit.Check,
    onFix: () -> Unit,
) {
    val granted = check.status == PermissionAudit.Status.GRANTED
    val skipped = check.status == PermissionAudit.Status.NOT_APPLICABLE
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
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
    val iconRes = when {
        skipped -> R.drawable.ic_radio_unchecked
        granted -> R.drawable.ic_check_circle
        else -> R.drawable.ic_warning
    }
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(24.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandTipsCard() {
    val detected = remember { DeviceBrandDetector.current() }
    var selected by remember { mutableStateOf(detected) }
    var expanded by remember { mutableStateOf(false) }
    Column {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(
                text = stringResource(selected.labelRes()),
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
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

        Text(
            text = stringResource(selected.keepAwakeTipsRes()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun VoiceStatusBanner(status: DefaultAlarmDetector.DefaultStatus) {
    // Both banner icons are colored PNG drawables; tint stays Unspecified
    // so each renders with its own palette.
    val checkCirclePainter = painterResource(R.drawable.ic_check_circle)
    val infoPainter = painterResource(R.drawable.ic_info)
    val spec = when (status) {
        is DefaultAlarmDetector.DefaultStatus.WeAreDefault ->
            BannerSpec(checkCirclePainter, R.string.help_status_default, null)
        is DefaultAlarmDetector.DefaultStatus.Disambiguator ->
            BannerSpec(infoPainter, R.string.help_status_disambiguator, null)
        is DefaultAlarmDetector.DefaultStatus.OtherIsDefault ->
            BannerSpec(infoPainter, R.string.help_status_other, status.displayName)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = spec.icon,
                contentDescription = null,
                tint = Color.Unspecified,
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
    val textRes: Int,
    val arg: String?,
)

@Composable
private fun DebugContent(onFireAlarmNow: () -> Unit) {
    Text(
        text = stringResource(R.string.help_debug_body),
        style = MaterialTheme.typography.bodyMedium,
    )
    FilledTonalButton(
        onClick = onFireAlarmNow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(stringResource(R.string.help_debug_fire_now_button))
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
