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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
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
    recentSearches: List<String>,
    searchSuggestions: List<String>,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectFilter: (SearchFilter, String) -> Unit,
    onSearch: (String, SearchFilter) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onShortClick: (MediaItem) -> Unit,
    onChannelClick: (channelId: String, name: String, avatar: String) -> Unit,
    modifier: Modifier = Modifier,
    downloadsByVideoId: Map<String, DownloadedVideoEntity> = emptyMap(),
    onRetryDownload: (DownloadedVideoEntity) -> Unit = {}
) {
    var queryField by remember { mutableStateOf(TextFieldValue("")) }
    var showSuggestionsOverlay by rememberSaveable { mutableStateOf(true) }
    val query = queryField.text
    val keyboard = LocalSoftwareKeyboardController.current
    val quickDiscoveryChips = listOf(
        "Pakistani Drama",
        "CID Full Episode",
        "Cricket Highlights",
        "4K HDR Nature",
        "Tech Reviews",
        "LoFi Beats"
    )

    BackHandler {
        if (showSuggestionsOverlay && searchState != null) {
            showSuggestionsOverlay = false
        } else {
            onBack()
        }
    }

    fun submitQuery(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        queryField = TextFieldValue(clean, selection = TextRange(clean.length))
        showSuggestionsOverlay = false
        keyboard?.hide()
        onSearch(clean, selectedFilter)
    }

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
                    onClick = {
                        if (showSuggestionsOverlay && searchState != null) {
                            showSuggestionsOverlay = false
                            keyboard?.hide()
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                OutlinedTextField(
                    value = queryField,
                    onValueChange = { next ->
                        queryField = next
                        showSuggestionsOverlay = true
                        onQueryChange(next.text)
                    },
                    placeholder = { Text("Search videos, channels, Shorts…") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        queryField = TextFieldValue("")
                                        showSuggestionsOverlay = true
                                        onQueryChange("")
                                    }
                                ) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                                }
                            }
                            IconButton(onClick = { submitQuery(query) }) {
                                Icon(Icons.Rounded.Search, contentDescription = "Run search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { submitQuery(query) }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                showSuggestionsOverlay = true
                            }
                        }
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
                            showSuggestionsOverlay = false
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

            val shouldShowSuggestPane = showSuggestionsOverlay || searchState == null

            if (shouldShowSuggestPane) {
                val trimmedQuery = query.trim()
                val matchingRecent = remember(recentSearches, trimmedQuery) {
                    if (trimmedQuery.isEmpty()) recentSearches
                    else recentSearches.filter { it.contains(trimmedQuery, ignoreCase = true) }
                }
                val liveFiltered = remember(searchSuggestions, matchingRecent) {
                    val seen = matchingRecent.map { it.lowercase() }.toHashSet()
                    searchSuggestions.filter { seen.add(it.lowercase()) }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("search_suggestions_list"),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    if (trimmedQuery.isEmpty()) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(quickDiscoveryChips) { chip ->
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clickable { submitQuery(chip) }
                                    ) {
                                        Text(
                                            text = chip,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (recentSearches.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Recent searches",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = onClearRecentSearches) {
                                        Text("Clear all")
                                    }
                                }
                            }
                        }
                    }

                    items(matchingRecent, key = { "recent_$it" }) { recent ->
                        SearchSuggestionRow(
                            text = recent,
                            isHistory = true,
                            onSelect = { submitQuery(recent) },
                            onFillQuery = {
                                val next = "$recent "
                                queryField = TextFieldValue(next, selection = TextRange(next.length))
                                showSuggestionsOverlay = true
                                onQueryChange(next)
                            },
                            onRemove = { onRemoveRecentSearch(recent) }
                        )
                    }

                    if (trimmedQuery.isNotEmpty()) {
                        items(liveFiltered, key = { "live_$it" }) { suggestion ->
                            SearchSuggestionRow(
                                text = suggestion,
                                isHistory = false,
                                onSelect = { submitQuery(suggestion) },
                                onFillQuery = {
                                    val next = "$suggestion "
                                    queryField = TextFieldValue(next, selection = TextRange(next.length))
                                    showSuggestionsOverlay = true
                                    onQueryChange(next)
                                },
                                onRemove = null
                            )
                        }
                    }

                    if (trimmedQuery.isEmpty() && recentSearches.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Type anything to see live keyword suggestions or search videos, Shorts & channels.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                when (searchState) {
                    null -> Unit

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
}

@Composable
private fun SearchSuggestionRow(
    text: String,
    isHistory: Boolean,
    onSelect: () -> Unit,
    onFillQuery: () -> Unit,
    onRemove: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isHistory) Icons.Rounded.History else Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove recent search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = onFillQuery,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.NorthWest,
                    contentDescription = "Insert into search box",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
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
