package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    downloadProgress: Map<String, Float>,
    offlineMode: Boolean,
    onRefreshWithMood: (String?) -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onToggleLike: (Track) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onShareClick: (Track) -> Unit,
    onRecordDwell: (Track, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val moodChips = listOf("Late Night Focus", "Energetic Drop", "Chill Ambient", "Euphoric Synth", "Acoustic Calm")
    var selectedMood by remember { mutableStateOf<String?>(null) }
    var customDjPrompt by remember { mutableStateOf("") }

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
                    onClick = { onRefreshWithMood(selectedMood) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_recommendations_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh recommendations")
                }
            }
        }

        // AI Mood DJ Banner
        item {
            ObsidianGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp,
                accentTint = UltravioletPulse
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_sonic_dna_banner),
                        contentDescription = "AI Mood DJ backdrop",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x55090B10), Color(0xEA090B10))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MintElectric,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "AI MOOD DJ",
                                style = MaterialTheme.typography.labelLarge,
                                color = MintElectric
                            )
                            if (discoveryState is UiState.Success && discoveryState.data.poweredByGemini) {
                                Surface(
                                    color = UltravioletPulse.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Gemini Re-Ranked",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        val headline = (discoveryState as? UiState.Success)?.data?.djHeadline
                            ?: "Tuning your personalized wave..."
                        val sub = (discoveryState as? UiState.Success)?.data?.djSubheadline
                            ?: "Analyzing replays, completion rates, and time of day"

                        Text(
                            text = headline,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD1D5DB),
                            maxLines = 2
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(moodChips) { chip ->
                            FilterChip(
                                selected = selectedMood == chip,
                                onClick = {
                                    selectedMood = if (selectedMood == chip) null else chip
                                    onRefreshWithMood(selectedMood)
                                },
                                label = { Text(chip) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customDjPrompt,
                            onValueChange = { customDjPrompt = it },
                            placeholder = { Text("Ask AI DJ (e.g. Arijit Singh, midnight drive)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_dj_prompt_input")
                        )
                        Button(
                            onClick = {
                                if (customDjPrompt.isNotBlank()) {
                                    selectedMood = customDjPrompt.trim()
                                    onRefreshWithMood(selectedMood)
                                }
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("ai_dj_tune_button")
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = "Tune AI DJ")
                        }
                    }
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
                            Button(onClick = { onRefreshWithMood(selectedMood) }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
            is UiState.Success -> {
                val bundle = discoveryState.data
                val allQueue = (bundle.heroPicks + bundle.explorePicks).map { it.track }.distinctBy { it.id }

                if (bundle.heroPicks.isEmpty()) {
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
                    item {
                        Text(
                            text = "Top Personalized Picks",
                            style = MaterialTheme.typography.titleLarge
                        )
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
