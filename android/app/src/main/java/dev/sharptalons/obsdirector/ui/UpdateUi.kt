package dev.sharptalons.obsdirector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.sharptalons.obsdirector.update.UpdateController as vm
import dev.sharptalons.obsdirector.update.Updates

/**
 * The prompt, mounted once over the whole app by AppShell. Update downloads and opens Android's installer; Ignore stops asking for this
 * version (Settings still offers it); Back asks again next start. While downloading it can't
 * be dismissed and shows a percentage.
 */
@Composable
fun UpdateDialog(u: Updates.Release) {
    val prog = vm.updateProgress
    AlertDialog(
        onDismissRequest = { if (prog == null) vm.updatePrompt = false },
        properties = DialogProperties(dismissOnBackPress = prog == null, dismissOnClickOutside = prog == null),
        containerColor = T.surface,
        titleContentColor = T.text,
        textContentColor = T.text,
        title = { Text("${Updates.NAME} ${u.version} is available", style = Type.cardTitle) },
        text = {
            Text(
                if (prog != null) "Downloading… ${(prog * 100).toInt()}%. Android will then ask you to install it."
                else "You have ${Updates.CURRENT_VERSION}. Update downloads the new version" +
                    (if (u.apkBytes > 0) " (${(u.apkBytes + 500_000) / 1_000_000} MB)" else "") +
                    " and Android asks you to install it. Your clips, saved session audio, paired PC and settings are kept.",
                style = Type.body,
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.installUpdate() }, enabled = prog == null) {
                Text("Update", style = Type.body.copy(color = if (prog == null) T.text else T.muted, fontWeight = FontWeight.SemiBold))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.ignoreUpdate() }, enabled = prog == null) {
                Text("Ignore", style = Type.body.copy(color = T.muted))
            }
        },
    )
}

/** The first row of Settings › Updates and about: the installed version, what the last check found, and one action. */
@Composable
fun UpdateRow() {
    val u = vm.update
    val prog = vm.updateProgress
    val context = LocalContext.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${Updates.NAME} ${Updates.CURRENT_VERSION}", style = Type.body.copy(color = if (u != null) T.accent else T.text, fontWeight = FontWeight.Medium))
                Text(
                    when {
                        prog != null -> "Downloading ${u?.version ?: "the update"}… ${(prog * 100).toInt()}%"
                        vm.updateStatus != null -> vm.updateStatus!!
                        else -> "New versions are checked on GitHub each time ${Updates.NAME} opens. Nothing about you or your phone is sent."
                    },
                    style = Type.small,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            when {
                prog != null -> OutlineButton("Downloading", Icons.Outlined.SystemUpdate, enabled = false, height = 34.dp) {}
                u != null -> PrimaryButton("Update to ${u.version}", Icons.Outlined.SystemUpdate, height = 34.dp) { vm.installUpdate() }
                else -> OutlineButton(if (vm.updateChecking) "Checking…" else "Check for updates", Icons.Outlined.Refresh, enabled = !vm.updateChecking, height = 34.dp) {
                    vm.checkForUpdates(manual = true)
                }
            }
        }
        HorizontalDivider(color = T.rule)
        SettingRow("Release page") {
            OutlineButton("Open", Icons.AutoMirrored.Outlined.OpenInNew, height = 34.dp) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u?.page ?: "https://github.com/${Updates.RELEASES_REPO}/releases/latest")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
