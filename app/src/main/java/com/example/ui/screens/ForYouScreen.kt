package com.example.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DiscoveryFeedBundle
import com.example.data.model.RecommendedPick
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.ui.components.DarkMoodMixesStrip
import com.example.ui.components.MoodMixSpec
import com.example.ui.components.MosaicTileSpecs
import com.example.ui.components.MusicMosaicDarkSection
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.PulseScreenHeader
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
            PulseScreenHeader(
                title = "For You",
                titleTestTag = "for_you_header_title",
                trailingAction = {
                    IconButton(
                        onClick = { onRefreshWithMood(null) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_recommendations_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh recommendations",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            )
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
                val allQueue = (bundle.heroPicks + bundle.explorePicks)
                    .map { it.track }
                    .distinctBy { it.id }

                val ytMosaicPool = (bundle.youtubeLikedRecs + allQueue)
                    .filter { it.sourceType == TrackSourceType.YOUTUBE }
                    .distinctBy { it.id }
                val mosaicTracks = ytMosaicPool
                    .ifEmpty { allQueue }
                    .take(MosaicTileSpecs.size)

                if (bundle.heroPicks.isEmpty() && mosaicTracks.isEmpty()) {
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
                    val mosaicQueue = (mosaicTracks + allQueue).distinctBy { it.id }
                    item(key = "music_mosaic_dark") {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            MusicMosaicDarkSection(
                                tracks = mosaicTracks,
                                activeTrackId = activeTrackId,
                                isPlaying = isPlaying,
                                activeMoodMix = activeMoodMix,
                                onPlayTrack = { track -> onPlayTrack(track, mosaicQueue) },
                                onPlayMoodMix = onPlayMoodMix,
                                footerContent = { scaleDp, scalePx, isDarkTheme ->
                                    DarkMoodMixesStrip(
                                        scaleDp = scaleDp,
                                        scalePx = scalePx,
                                        isDarkTheme = isDarkTheme,
                                        activeMoodName = activeMoodMix,
                                        isPlaying = isPlaying,
                                        onMixClick = onPlayMoodMix
                                    )
                                }
                            )
                        }
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

                    if (bundle.explorePicks.isNotEmpty()) {
                        item {
                            Text("Deep Discovery Queue", style = MaterialTheme.typography.titleLarge)
                        }
                        items(bundle.explorePicks, key = { "explore_${it.track.id}" }) { pick ->
                            TrackRowItem(
                                track = pick.track,
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
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
