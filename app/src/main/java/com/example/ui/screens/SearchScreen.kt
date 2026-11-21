package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.DownloadedVideoEntity
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.SearchFilter
import com.example.mediaserviceinterfaces.SearchResultBundle
import com.example.ui.UiState
import com.example.ui.components.VideoCard

@Composable
fun SearchScreen(
    searchState: UiState<SearchResultBundle>?,
    selectedFilter: SearchFilter,
    onBack: () -> Unit,
    onSelectFilter: (SearchFilter, String) -> Unit,
    onSearch: (String, SearchFilter) -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onShortClick: (MediaItem) -> Unit,
    onChannelClick: (channelId: String, name: String, avatar: String) -> Unit,
    modifier: Modifier = Modifier,
    downloadsByVideoId: Map<String, DownloadedVideoEntity> = emptyMap(),
    onRetryDownload: (DownloadedVideoEntity) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val suggestions = listOf("4K HDR Nature", "Tech Reviews", "LoFi Beats", "Android Kotlin", "Cricket Highlights")

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search videos, channels, Shorts…") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                                }
                            }
                            IconButton(
                                onClick = {
                                    keyboard?.hide()
                                    onSearch(query, selectedFilter)
                                }
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = "Run search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            onSearch(query, selectedFilter)
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .testTag("search_input")
                )
            }

            // Search Filter Chips: All / Videos / Shorts / Channels
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SearchFilter.entries) { filter ->
                    val isSelected = filter == selectedFilter
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            keyboard?.hide()
                            onSelectFilter(filter, query)
                        },
                        label = {
                            Text(
                                text = filter.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("search_filter_${filter.name.lowercase()}")
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions) { chip ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable {
                                query = chip
                                keyboard?.hide()
                                onSearch(chip, selectedFilter)
                            }
                    ) {
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            when (searchState) {
                null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Search long videos, or select a filter for Shorts & channels",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                is UiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = searchState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                is UiState.Success -> {
                    val bundle = searchState.data
                    if (bundle.channels.isEmpty() && bundle.shorts.isEmpty() && bundle.videos.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No ${selectedFilter.label.lowercase()} found for \"${bundle.query}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 310.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (bundle.channels.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = "Channels",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                items(bundle.channels, key = { "sch_${it.channelId}" }) { ch ->
                                    ChannelRowCard(
                                        channelId = ch.channelId,
                                        name = ch.name,
                                        avatarUrl = ch.avatarUrl,
                                        subtitle = listOf(ch.subscriberCountText, ch.videoCountText)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" • ")
                                            .ifBlank { "Open channel" },
                                        isVerified = ch.isVerified,
                                        onClick = { onChannelClick(ch.channelId, ch.name, ch.avatarUrl) }
                                    )
                                }
                            }

                            if (bundle.shorts.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column {
                                        Text(
                                            text = "Shorts",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            items(bundle.shorts, key = { "sshort_${it.id}" }) { shortItem ->
                                                SearchShortCard(
                                                    item = shortItem,
                                                    onClick = { onShortClick(shortItem) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (bundle.videos.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = "Videos",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                items(bundle.videos, key = { "svid_${it.id}" }) { video ->
                                    VideoCard(
                                        item = video,
                                        onVideoClick = onVideoClick,
                                        onChannelClick = onChannelClick,
                                        downloadEntity = downloadsByVideoId[video.id],
                                        onRetryDownload = onRetryDownload
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchShortCard(
    item: MediaItem,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(142.dp)
            .aspectRatio(9f / 16f)
            .clickable(onClick = onClick)
            .testTag("search_short_${item.id}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = "SHORTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.viewCountText.isNotBlank()) {
                    Text(
                        text = item.viewCountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
