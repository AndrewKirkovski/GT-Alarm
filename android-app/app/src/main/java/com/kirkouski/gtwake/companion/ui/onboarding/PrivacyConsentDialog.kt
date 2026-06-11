package com.kirkouski.gtwake.companion.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirkouski.gtwake.companion.R

/**
 * First-launch privacy-policy consent gate (AppGallery review rule 7.5: the
 * app must prompt the user to read the privacy policy on first launch).
 *
 * Non-dismissable (empty `onDismissRequest` — no tap-outside / back to skip):
 * the user must explicitly Agree to continue or Disagree to exit. The strings
 * are localised (the zh-rCN overlay carries the Chinese text required by
 * rule 7.1 for the Chinese-mainland release region).
 */
@Composable
fun PrivacyConsentDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
    onReadPolicy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.privacy_consent_title)) },
        text = {
            Column {
                Text(stringResource(R.string.privacy_consent_body))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.privacy_consent_read_policy),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onReadPolicy),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text(stringResource(R.string.privacy_consent_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) {
                Text(stringResource(R.string.privacy_consent_disagree))
            }
        },
    )
}
