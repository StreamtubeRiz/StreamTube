package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.OutfitDisplayFamily

/**
 * Renders the branded title "StreamTube" with "Riz" in superscript just like a ™ mark.
 */
@Composable
fun StreamTubeRizBrandText(
    modifier: Modifier = Modifier,
    mainSize: TextUnit = 22.sp,
    supSize: TextUnit = 11.sp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground
    val annotated = buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontFamily = OutfitDisplayFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = mainSize,
                color = textColor,
                letterSpacing = (-0.5).sp
            )
        ) {
            append("StreamTube")
        }
        withStyle(
            SpanStyle(
                fontFamily = OutfitDisplayFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = supSize,
                color = primaryColor,
                baselineShift = BaselineShift.Superscript,
                letterSpacing = 0.2.sp
            )
        ) {
            append("Riz")
        }
    }
    Text(
        text = annotated,
        modifier = modifier
    )
}

@Composable
fun StreamTubeTopBar(
    onSearchClick: () -> Unit,
    applyStatusBarsPadding: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .then(if (applyStatusBarsPadding) Modifier.statusBarsPadding() else Modifier)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StreamTubeRizBrandText(
                mainSize = 23.sp,
                supSize = 11.sp
            )

            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("top_search_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search videos, Shorts, and channels",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
fun ChannelAvatar(
    avatarUrl: String,
    channelName: String,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val initials = channelName
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "ST" }

    val clickModifier = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier.then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(avatarUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "$channelName channel logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
