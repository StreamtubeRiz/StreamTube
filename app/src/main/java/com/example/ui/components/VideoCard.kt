package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.local.DownloadedVideoEntity
import com.example.mediaserviceinterfaces.MediaItem
import com.example.ui.theme.EmeraldSuccess

/**
 * Clean YouTube-style VideoCard showing 16:9 thumbnail, duration, channel logo,
 * title, channel name, views, and upload date/time without feed-cluttering buttons.
 */
@Composable
fun VideoCard(
    item: MediaItem,
    onVideoClick: (MediaItem) -> Unit,
    onChannelClick: (channelId: String, name: String, avatar: String) -> Unit,
    modifier: Modifier = Modifier,
    downloadEntity: DownloadedVideoEntity? = null,
    onRetryDownload: ((DownloadedVideoEntity) -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onVideoClick(item) }
            .testTag("video_card_${item.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "${item.title} thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = {
                        SubcomposeAsyncImage(
                            model = item.fallbackThumbnailUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                            )
                        )
                )

                if (item.isLive || item.isShort) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.isLive) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.78f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (item.isLive) "LIVE" else "SHORTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                if (item.durationText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.isLive) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.84f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = item.durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                ChannelAvatar(
                    avatarUrl = item.channelAvatarUrl,
                    channelName = item.channelName,
                    size = 40.dp,
                    onClick = {
                        if (item.channelId.isNotBlank() || item.channelName.isNotBlank()) {
                            onChannelClick(item.channelId, item.channelName, item.channelAvatarUrl)
                        }
                    },
                    modifier = Modifier.testTag("channel_avatar_${item.id}")
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val metaParts = buildList {
                        add(item.channelName.ifBlank { "Creator" })
                        if (item.viewCountText.isNotBlank()) add(item.viewCountText)
                        if (item.displayDateTime.isNotBlank()) add(item.displayDateTime)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = metaParts.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (downloadEntity != null) {
                VideoCardDownloadStrip(
                    entity = downloadEntity,
                    onRetry = onRetryDownload
                )
            }
        }
    }
}

@Composable
private fun VideoCardDownloadStrip(
    entity: DownloadedVideoEntity,
    onRetry: ((DownloadedVideoEntity) -> Unit)?
) {
    val status = entity.status
    val isPreparing = status == DownloadedVideoEntity.STATUS_QUEUED ||
        (status == DownloadedVideoEntity.STATUS_DOWNLOADING && entity.downloadedBytes <= 0L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                when (status) {
                    DownloadedVideoEntity.STATUS_COMPLETED -> Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(14.dp)
                    )
                    DownloadedVideoEntity.STATUS_FAILED -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    else -> Unit
                }
                if (status == DownloadedVideoEntity.STATUS_COMPLETED || status == DownloadedVideoEntity.STATUS_FAILED) {
                    Spacer(modifier = Modifier.width(5.dp))
                }
                val label = when {
                    isPreparing -> "Preparing ${entity.qualityLabel}…"
                    status == DownloadedVideoEntity.STATUS_DOWNLOADING -> "Downloading ${entity.qualityLabel} • ${(entity.progressFraction * 100).toInt()}%"
                    status == DownloadedVideoEntity.STATUS_MERGING -> "Merging ${entity.qualityLabel}…"
                    status == DownloadedVideoEntity.STATUS_COMPLETED -> "Saved ${entity.qualityLabel}"
                    status == DownloadedVideoEntity.STATUS_FAILED -> "Failed ${entity.qualityLabel}"
                    else -> entity.qualityLabel
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (status) {
                        DownloadedVideoEntity.STATUS_COMPLETED -> EmeraldSuccess
                        DownloadedVideoEntity.STATUS_FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (status == DownloadedVideoEntity.STATUS_FAILED && onRetry != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .clickable { onRetry(entity) }
                        .testTag("card_retry_download_${entity.videoId}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Retry download",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        if (isPreparing || status == DownloadedVideoEntity.STATUS_MERGING) {
            Spacer(modifier = Modifier.height(5.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.tertiary
            )
        } else if (status == DownloadedVideoEntity.STATUS_DOWNLOADING) {
            Spacer(modifier = Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { entity.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}
