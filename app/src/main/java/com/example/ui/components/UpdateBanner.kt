package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.update.AppUpdateState
import com.example.update.GitHubReleaseInfo
import java.io.File
import java.util.Locale

fun AppUpdateState.shouldShowTopBanner(isDismissed: Boolean = false): Boolean {
    if (isDismissed) return false
    return when (this) {
        is AppUpdateState.UpdateAvailable,
        is AppUpdateState.Downloading,
        is AppUpdateState.ReadyToInstall,
        is AppUpdateState.Error -> true

        AppUpdateState.Idle,
        AppUpdateState.UpToDate -> false
    }
}

/**
 * Top banner shown whenever a newer GitHub Release exists, with live APK download progress,
 * one-tap install, and a close button to hide the prompt until the next release arrives.
 */
@Composable
fun UpdateBanner(
    state: AppUpdateState,
    onStartUpdate: (GitHubReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    modifier: Modifier = Modifier,
    isDismissed: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    var showReleaseNotes by remember { mutableStateOf(false) }

    val activeRelease: GitHubReleaseInfo? = when (state) {
        is AppUpdateState.UpdateAvailable -> state.release
        is AppUpdateState.Downloading -> state.release
        is AppUpdateState.ReadyToInstall -> state.release
        is AppUpdateState.Error -> state.release
        else -> null
    }

    AnimatedVisibility(
        visible = state.shouldShowTopBanner(isDismissed),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            modifier = modifier
                .fillMaxWidth()
                .testTag("top_update_banner")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = activeRelease != null) { showReleaseNotes = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                when (state) {
                    is AppUpdateState.UpdateAvailable -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = "App update available",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Update this app for best smooth experience",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "New version v${state.release.versionName} is ready • Tap for notes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = { onStartUpdate(state.release) },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("banner_update_button")
                            ) {
                                Text(
                                    text = "Update",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("banner_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close update banner",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    is AppUpdateState.Downloading -> {
                        val pct = (state.progressFraction * 100f).toInt().coerceIn(0, 100)
                        val downloadedMb = String.format(Locale.US, "%.1f", state.downloadedBytes / (1024f * 1024f))
                        val totalMbText = if (state.totalBytes > 0L) {
                            String.format(Locale.US, "%.1f MB", state.totalBytes / (1024f * 1024f))
                        } else {
                            "..."
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Downloading v${state.release.versionName}…",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$pct% ($downloadedMb / $totalMbText)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("banner_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Hide update banner",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (state.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { state.progressFraction.coerceIn(0f, 1f) },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .testTag("update_progress_bar")
                            )
                        } else {
                            LinearProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .testTag("update_progress_bar")
                            )
                        }
                    }

                    is AppUpdateState.ReadyToInstall -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.InstallMobile,
                                    contentDescription = "Ready to install update",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "v${state.release.versionName} downloaded!",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Tap Install to update StreamTubeᴿⁱᶻ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = { onInstallUpdate(state.apkFile) },
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("banner_install_button")
                            ) {
                                Text(
                                    text = "Install",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("banner_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close update banner",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    is AppUpdateState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = "Update error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Update this app for best smooth experience",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = { onStartUpdate(state.release) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("banner_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close update banner",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    if (showReleaseNotes && activeRelease != null) {
        AlertDialog(
            onDismissRequest = { showReleaseNotes = false },
            title = {
                Text(
                    text = "${activeRelease.title} (v${activeRelease.versionName})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = activeRelease.releaseNotes.ifBlank {
                        "Includes faster zero-buffer streaming, smoother Shorts search playback, HD downloads, and performance improvements."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                when (state) {
                    is AppUpdateState.ReadyToInstall -> {
                        Button(onClick = {
                            showReleaseNotes = false
                            onInstallUpdate(state.apkFile)
                        }) {
                            Text("Install APK")
                        }
                    }

                    is AppUpdateState.Downloading -> {
                        TextButton(onClick = { showReleaseNotes = false }) {
                            Text("Downloading…")
                        }
                    }

                    else -> {
                        Button(onClick = {
                            showReleaseNotes = false
                            onStartUpdate(activeRelease)
                        }) {
                            Text("Download Update")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showReleaseNotes = false }) {
                    Text("Close")
                }
            }
        )
    }
}
