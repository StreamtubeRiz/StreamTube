package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.DownloadedVideoEntity
import com.example.data.local.LikedVideoEntity
import com.example.data.local.SavedVideoEntity
import com.example.mediaserviceinterfaces.MediaItem
import com.example.ui.theme.EmeraldSuccess

private enum class LibraryTab(val label: String, val icon: ImageVector) {
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    SAVED("Saved", Icons.Rounded.Bookmark),
    LIKED("Liked", Icons.Rounded.ThumbUp)
}

@Composable
fun DownloadsScreen(
    downloads: List<DownloadedVideoEntity>,
    onPlayOffline: (MediaItem, String) -> Unit,
    onRetryDownload: (DownloadedVideoEntity) -> Unit,
    onDeleteDownload: (DownloadedVideoEntity) -> Unit,
    modifier: Modifier = Modifier,
    savedVideos: List<SavedVideoEntity> = emptyList(),
    likedVideos: List<LikedVideoEntity> = emptyList(),
    onPlayLibraryVideo: (MediaItem) -> Unit = {},
    onRemoveSaved: (String) -> Unit = {},
    onRemoveLiked: (String) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.DOWNLOADS) }

    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(LibraryTab.entries) { tab ->
                val count = when (tab) {
                    LibraryTab.DOWNLOADS -> downloads.size
                    LibraryTab.SAVED -> savedVideos.size
                    LibraryTab.LIKED -> likedVideos.size
                }
                val selected = selectedTab == tab
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (selected) null else BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable { selectedTab = tab }
                        .testTag("library_tab_${tab.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (count > 0) "${tab.label} ($count)" else tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        when (selectedTab) {
            LibraryTab.DOWNLOADS -> {
                if (downloads.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_offline_cinema_1795166886702),
                                contentDescription = "Offline downloads illustration",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth(0.88f)
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(20.dp))
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = stringResource(R.string.empty_downloads_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.empty_downloads_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("downloads_grid")
                    ) {
                        items(downloads, key = { it.downloadKey }) { entry ->
                            val isCompleted = entry.status == DownloadedVideoEntity.STATUS_COMPLETED
                            val isFailed = entry.status == DownloadedVideoEntity.STATUS_FAILED
                            val isQueued = entry.status == DownloadedVideoEntity.STATUS_QUEUED
                            val isMerging = entry.status == DownloadedVideoEntity.STATUS_MERGING

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = isCompleted) {
                                        val item = MediaItem(
                                            id = entry.videoId,
                                            title = entry.title,
                                            thumbnailUrl = entry.thumbnailUrl,
                                            channelId = entry.channelId,
                                            channelName = entry.channelName,
                                            channelAvatarUrl = entry.channelAvatarUrl,
                                            viewCountText = entry.viewCountText,
                                            publishedDateText = entry.publishedDateText,
                                            durationText = entry.durationText
                                        )
                                        onPlayOffline(item, entry.localFilePath)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .height(72.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    ) {
                                        AsyncImage(
                                            model = entry.thumbnailUrl,
                                            contentDescription = entry.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        if (isCompleted) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayCircle,
                                                contentDescription = "Play offline",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .align(Alignment.Center)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${entry.channelName} • ${entry.qualityLabel} (${entry.container})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))

                                        when {
                                            isCompleted -> {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.DownloadDone,
                                                        contentDescription = null,
                                                        tint = EmeraldSuccess,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    val mb = entry.totalBytes / (1024.0 * 1024.0)
                                                    Text(
                                                        text = String.format("Saved • %.1f MB", mb),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = EmeraldSuccess,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            isFailed -> {
                                                Column(
                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.ErrorOutline,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "Download failed",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.error,
                                                            maxLines = 1,
                                                            softWrap = false,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(50),
                                                        color = MaterialTheme.colorScheme.errorContainer,
                                                        modifier = Modifier
                                                            .clickable { onRetryDownload(entry) }
                                                            .testTag("downloads_retry_${entry.formatItag}")
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Refresh,
                                                                contentDescription = "Retry download",
                                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(5.dp))
                                                            Text(
                                                                text = "Retry",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                                maxLines = 1,
                                                                softWrap = false
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            isQueued || (entry.status == DownloadedVideoEntity.STATUS_DOWNLOADING && entry.downloadedBytes <= 0L) -> {
                                                Column {
                                                    LinearProgressIndicator(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp)),
                                                        color = MaterialTheme.colorScheme.tertiary
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Preparing & starting download…",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            isMerging -> {
                                                Column {
                                                    LinearProgressIndicator(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp)),
                                                        color = MaterialTheme.colorScheme.tertiary
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Merging video & audio…",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }

                                            else -> {
                                                val dlMb = entry.downloadedBytes / (1024.0 * 1024.0)
                                                val totMb = entry.totalBytes / (1024.0 * 1024.0)
                                                val sizeText = if (entry.totalBytes > 0L) {
                                                    String.format(" (%.1f / %.1f MB)", dlMb, totMb)
                                                } else ""
                                                Column {
                                                    LinearProgressIndicator(
                                                        progress = { entry.progressFraction },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp)),
                                                        color = MaterialTheme.colorScheme.tertiary
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Downloading ${(entry.progressFraction * 100).toInt()}%$sizeText",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = { onDeleteDownload(entry) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete download",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.SAVED -> {
                if (savedVideos.isEmpty()) {
                    EmptyLibraryState(
                        icon = Icons.Rounded.Bookmark,
                        title = "No Saved Videos Yet",
                        subtitle = "Tap Save below any video player to bookmark it here for quick access."
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("saved_videos_grid")
                    ) {
                        items(savedVideos, key = { it.videoId }) { entry ->
                            LibraryVideoCard(
                                title = entry.title,
                                channelName = entry.channelName,
                                thumbnailUrl = entry.thumbnailUrl,
                                viewCountText = entry.viewCountText,
                                publishedDateText = entry.publishedDateText,
                                durationText = entry.durationText,
                                onPlay = {
                                    onPlayLibraryVideo(
                                        MediaItem(
                                            id = entry.videoId,
                                            title = entry.title,
                                            thumbnailUrl = entry.thumbnailUrl,
                                            channelId = entry.channelId,
                                            channelName = entry.channelName,
                                            channelAvatarUrl = entry.channelAvatarUrl,
                                            viewCountText = entry.viewCountText,
                                            publishedDateText = entry.publishedDateText,
                                            durationText = entry.durationText
                                        )
                                    )
                                },
                                onRemove = { onRemoveSaved(entry.videoId) }
                            )
                        }
                    }
                }
            }

            LibraryTab.LIKED -> {
                if (likedVideos.isEmpty()) {
                    EmptyLibraryState(
                        icon = Icons.Rounded.ThumbUp,
                        title = "No Liked Videos Yet",
                        subtitle = "Tap Like below any video player to save your favorites here."
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("liked_videos_grid")
                    ) {
                        items(likedVideos, key = { it.videoId }) { entry ->
                            LibraryVideoCard(
                                title = entry.title,
                                channelName = entry.channelName,
                                thumbnailUrl = entry.thumbnailUrl,
                                viewCountText = entry.viewCountText,
                                publishedDateText = entry.publishedDateText,
                                durationText = entry.durationText,
                                onPlay = {
                                    onPlayLibraryVideo(
                                        MediaItem(
                                            id = entry.videoId,
                                            title = entry.title,
                                            thumbnailUrl = entry.thumbnailUrl,
                                            channelId = entry.channelId,
                                            channelName = entry.channelName,
                                            channelAvatarUrl = entry.channelAvatarUrl,
                                            viewCountText = entry.viewCountText,
                                            publishedDateText = entry.publishedDateText,
                                            durationText = entry.durationText
                                        )
                                    )
                                },
                                onRemove = { onRemoveLiked(entry.videoId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LibraryVideoCard(
    title: String,
    channelName: String,
    thumbnailUrl: String,
    viewCountText: String,
    publishedDateText: String,
    durationText: String,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = "Play video",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                )
                if (durationText.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = channelName.ifBlank { "Creator" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOf(viewCountText, publishedDateText)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove video",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
