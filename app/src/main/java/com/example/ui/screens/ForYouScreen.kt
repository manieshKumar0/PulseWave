package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DiscoveryFeedBundle
import com.example.data.model.RecommendedPick
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.ui.components.AskAiDjBanner
import com.example.ui.components.MoodMixSpec
import com.example.ui.components.MoodMixesCarouselCard
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.PulseWaveBrandLogo
import com.example.ui.components.TrackArtworkThumb
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse
import com.example.ui.viewmodel.UiState

@Composable
fun ForYouScreen(
    discoveryState: UiState<DiscoveryFeedBundle>,
    activeTrackId: String?,
    isPlaying: Boolean,
    activeMoodMix: String?,
    downloadProgress: Map<String, Float>,
    offlineMode: Boolean,
    onRefreshWithMood: (String?) -> Unit,
    onPlayMoodMix: (MoodMixSpec) -> Unit,
    onAskAiDj: (String) -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onToggleLike: (Track) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onShareClick: (Track) -> Unit,
    onRecordDwell: (Track, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    PulseWaveBrandLogo(size = 38.dp)
                    Column {
                        Text(
                            text = "For You",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = if (offlineMode) "Offline Behavioral Engine Active" else "Two-Stage Behavioral + Gemini AI Engine",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = { onRefreshWithMood(null) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_recommendations_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh recommendations")
                }
            }
        }

        when (discoveryState) {
            is UiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Scoring tracks & running Stage-2 re-ranker...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            is UiState.Error -> {
                item {
                    ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Couldn't refresh feed", style = MaterialTheme.typography.titleMedium)
                            Text(discoveryState.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { onRefreshWithMood(null) }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
            is UiState.Success -> {
                val bundle = discoveryState.data
                val allQueue = (bundle.heroPicks + bundle.replayPicks + bundle.explorePicks)
                    .map { it.track }
                    .distinctBy { it.id }

                val bentoTracks = bundle.youtubeLikedRecs
                    .filter { it.sourceType == TrackSourceType.YOUTUBE }
                    .ifEmpty { allQueue.filter { it.sourceType == TrackSourceType.YOUTUBE } }
                    .distinctBy { it.id }
                    .take(5)

                if (bundle.heroPicks.isEmpty() && bentoTracks.isEmpty()) {
                    item {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_empty_library),
                                    contentDescription = "Empty discovery",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                )
                                Text("No tracks ready in this mode", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Download tracks for offline playback or import device/cloud audio in Library.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    if (bentoTracks.isNotEmpty()) {
                        val bentoQueue = (bentoTracks + allQueue).distinctBy { it.id }
                        item(key = "featured_bento_collage") {
                            FeaturedBentoCollage(
                                tracks = bentoTracks,
                                activeTrackId = activeTrackId,
                                onPlayTrack = { track -> onPlayTrack(track, bentoQueue) }
                            )
                        }
                    }

                    item(key = "ask_ai_dj_banner") {
                        AskAiDjBanner(
                            onSubmitPrompt = onAskAiDj
                        )
                    }

                    item(key = "mood_mixes_carousel") {
                        MoodMixesCarouselCard(
                            activeMoodName = activeMoodMix,
                            isPlaying = isPlaying,
                            onMixClick = onPlayMoodMix
                        )
                    }

                    if (bundle.heroPicks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Top Personalized Picks",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }

                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(bundle.heroPicks, key = { it.track.id }) { pick ->
                                HeroRecommendationCard(
                                    pick = pick,
                                    isActive = pick.track.id == activeTrackId,
                                    onPlay = { onPlayTrack(pick.track, allQueue) },
                                    onShare = { onShareClick(pick.track) },
                                    onRecordDwell = { dwell -> onRecordDwell(pick.track, dwell) }
                                )
                            }
                        }
                    }

                    if (bundle.replayPicks.isNotEmpty()) {
                        item {
                            Text("Replay Radar", style = MaterialTheme.typography.titleLarge)
                        }
                        items(bundle.replayPicks, key = { "replay_${it.track.id}" }) { pick ->
                            RecommendationExplainRow(
                                pick = pick,
                                isActive = pick.track.id == activeTrackId,
                                downloadProgress = downloadProgress[pick.track.id],
                                onPlay = { onPlayTrack(pick.track, allQueue) },
                                onToggleLike = { onToggleLike(pick.track) },
                                onDownloadClick = { onDownloadClick(pick.track) },
                                onShareClick = { onShareClick(pick.track) }
                            )
                        }
                    }

                    if (bundle.explorePicks.isNotEmpty()) {
                        item {
                            Text("Deep Discovery Queue", style = MaterialTheme.typography.titleLarge)
                        }
                        items(bundle.explorePicks, key = { "explore_${it.track.id}" }) { pick ->
                            RecommendationExplainRow(
                                pick = pick,
                                isActive = pick.track.id == activeTrackId,
                                downloadProgress = downloadProgress[pick.track.id],
                                onPlay = { onPlayTrack(pick.track, allQueue) },
                                onToggleLike = { onToggleLike(pick.track) },
                                onDownloadClick = { onDownloadClick(pick.track) },
                                onShareClick = { onShareClick(pick.track) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedBentoCollage(
    tracks: List<Track>,
    activeTrackId: String?,
    onPlayTrack: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val gap = 10.dp
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("featured_bento_collage")
    ) {
        val totalHeight = (maxWidth * 0.82f).coerceIn(294.dp, 380.dp)

        when (tracks.size) {
            1 -> {
                BentoTrackCard(
                    track = tracks[0],
                    index = 0,
                    isActive = tracks[0].id == activeTrackId,
                    onClick = { onPlayTrack(tracks[0]) },
                    cornerRadius = 22.dp,
                    innerPadding = 16.dp,
                    titleSize = 21.sp,
                    titleLineHeight = 24.sp,
                    artistSize = 13.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight * 0.68f)
                )
            }
            2 -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight * 0.72f),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    BentoTrackCard(
                        track = tracks[0],
                        index = 0,
                        isActive = tracks[0].id == activeTrackId,
                        onClick = { onPlayTrack(tracks[0]) },
                        cornerRadius = 22.dp,
                        innerPadding = 14.dp,
                        titleSize = 20.sp,
                        titleLineHeight = 23.sp,
                        artistSize = 13.sp,
                        modifier = Modifier
                            .weight(0.60f)
                            .fillMaxHeight()
                    )
                    BentoTrackCard(
                        track = tracks[1],
                        index = 1,
                        isActive = tracks[1].id == activeTrackId,
                        onClick = { onPlayTrack(tracks[1]) },
                        cornerRadius = 20.dp,
                        innerPadding = 12.dp,
                        titleSize = 15.5.sp,
                        titleLineHeight = 18.sp,
                        artistSize = 12.sp,
                        modifier = Modifier
                            .weight(0.40f)
                            .fillMaxHeight()
                    )
                }
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    // Left column: large top hero + 2 compact bottom squares (or 1 wide bottom card if only 4 tracks)
                    Column(
                        modifier = Modifier
                            .weight(0.60f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        BentoTrackCard(
                            track = tracks[0],
                            index = 0,
                            isActive = tracks[0].id == activeTrackId,
                            onClick = { onPlayTrack(tracks[0]) },
                            cornerRadius = 22.dp,
                            innerPadding = 14.dp,
                            titleSize = 20.sp,
                            titleLineHeight = 23.sp,
                            artistSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(if (tracks.size == 3) 1f else 0.63f)
                        )

                        if (tracks.size >= 4) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.37f),
                                horizontalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                BentoTrackCard(
                                    track = tracks[2],
                                    index = 2,
                                    isActive = tracks[2].id == activeTrackId,
                                    onClick = { onPlayTrack(tracks[2]) },
                                    cornerRadius = 16.dp,
                                    innerPadding = 10.dp,
                                    titleSize = 12.5.sp,
                                    titleLineHeight = 14.5.sp,
                                    artistSize = 10.5.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                                if (tracks.size >= 5) {
                                    BentoTrackCard(
                                        track = tracks[3],
                                        index = 3,
                                        isActive = tracks[3].id == activeTrackId,
                                        onClick = { onPlayTrack(tracks[3]) },
                                        cornerRadius = 16.dp,
                                        innerPadding = 10.dp,
                                        titleSize = 12.5.sp,
                                        titleLineHeight = 14.5.sp,
                                        artistSize = 10.5.sp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }
                    }

                    // Right column: compact landscape top + tall portrait bottom
                    val tallRightTrack = if (tracks.size >= 5) tracks[4] else tracks.last()
                    Column(
                        modifier = Modifier
                            .weight(0.40f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        BentoTrackCard(
                            track = tracks[1],
                            index = 1,
                            isActive = tracks[1].id == activeTrackId,
                            onClick = { onPlayTrack(tracks[1]) },
                            cornerRadius = 20.dp,
                            innerPadding = 12.dp,
                            titleSize = 14.5.sp,
                            titleLineHeight = 16.5.sp,
                            artistSize = 11.5.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.37f)
                        )
                        BentoTrackCard(
                            track = tallRightTrack,
                            index = if (tracks.size >= 5) 4 else tracks.lastIndex,
                            isActive = tallRightTrack.id == activeTrackId,
                            onClick = { onPlayTrack(tallRightTrack) },
                            cornerRadius = 20.dp,
                            innerPadding = 12.dp,
                            titleSize = 15.5.sp,
                            titleLineHeight = 18.sp,
                            artistSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.63f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BentoTrackCard(
    track: Track,
    index: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp,
    innerPadding: Dp,
    titleSize: TextUnit,
    titleLineHeight: TextUnit,
    artistSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    )
                )
            )
            .border(
                width = if (isActive) 1.5.dp else 0.6.dp,
                color = if (isActive) MintElectric else Color.White.copy(alpha = 0.09f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .testTag("bento_track_card_$index")
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(28.dp)
        )

        val artUrl = track.displayArtworkUri
        if (artUrl.isNotBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = "Play ${track.title} by ${track.artist}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom contrast gradient scrim keeping upper artwork clear and bottom text legible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.40f to Color.Transparent,
                        0.72f to Color(0x5A000000),
                        1.0f to Color(0xD6000000)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = track.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.88f),
                fontWeight = FontWeight.Normal,
                fontSize = artistSize,
                lineHeight = artistSize * 1.2f,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroRecommendationCard(
    pick: RecommendedPick,
    isActive: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRecordDwell: (Long) -> Unit
) {
    DisposableEffect(pick.track.id) {
        val startMs = System.currentTimeMillis()
        onDispose {
            onRecordDwell(System.currentTimeMillis() - startMs)
        }
    }

    ObsidianGlassCard(
        modifier = Modifier
            .width(248.dp)
            .testTag("hero_pick_${pick.track.id}"),
        cornerRadius = 22.dp,
        accentTint = if (isActive) MintElectric else UltravioletPulse,
        onClick = onPlay
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TrackArtworkThumb(
                    artworkUrl = pick.track.displayArtworkUri,
                    title = pick.track.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(152.dp),
                    cornerRadius = 16.dp
                )
                Surface(
                    color = Color(0xBB090B10),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = pick.reasonTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MintElectric,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isActive) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                            contentDescription = "Play ${pick.track.title}",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = pick.track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${pick.track.artist} · ${pick.track.genre}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pick.explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pick.track.bpm} BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share track", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RecommendationExplainRow(
    pick: RecommendedPick,
    isActive: Boolean,
    downloadProgress: Float?,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onDownloadClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "✦ ${pick.reasonTag} — ${pick.explanation}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        TrackRowItem(
            track = pick.track,
            isActive = isActive,
            downloadProgress = downloadProgress,
            onPlay = onPlay,
            onToggleLike = onToggleLike,
            onDownloadClick = onDownloadClick,
            onShareClick = onShareClick
        )
    }
}
