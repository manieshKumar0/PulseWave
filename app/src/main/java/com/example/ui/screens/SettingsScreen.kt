package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.OfflineCacheSummary
import com.example.data.local.UserAppSettings
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.model.UserProfile
import androidx.compose.ui.graphics.Color
import com.example.ui.components.EqualizerControlPanel
import com.example.ui.components.GoogleGLogo
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.PulseWaveBrandLogo
import com.example.ui.theme.MintElectric
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.UltravioletPulse
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    currentUserProfile: UserProfile,
    settings: UserAppSettings,
    tracks: List<Track>,
    syncedCount: Int,
    sleepTimerRemainingMs: Long?,
    sleepTimerTotalMs: Long?,
    eqEnabled: Boolean,
    activeEqPreset: SoundscapePreset,
    eqBandLevels: List<Int>,
    eqBassBoost: Int,
    eqReverbPreset: Int,
    offlineCacheSummary: OfflineCacheSummary = OfflineCacheSummary(),
    isSyncingLocalLyrics: Boolean = false,
    isSignedIn: Boolean = true,
    isSigningIn: Boolean = false,
    onSignInWithGoogle: () -> Unit = {},
    onRequireSignIn: (String) -> Unit = { _ -> onSignInWithGoogle() },
    onToggleOfflineMode: (Boolean) -> Unit,
    onToggleHighResStreaming: (Boolean) -> Unit,
    onCrossfadeSecondsChange: (Int) -> Unit,
    onToggleEqEnabled: (Boolean) -> Unit,
    onSelectEqPreset: (SoundscapePreset) -> Unit,
    onUpdateEqBand: (Int, Int) -> Unit,
    onUpdateEqBassBoost: (Int) -> Unit,
    onUpdateEqReverb: (Int) -> Unit,
    onResetEqualizer: () -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onExtendSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onToggleDynamicGlow: (Boolean) -> Unit,
    onImportSafAudio: (List<Uri>, Boolean) -> Unit,
    onSyncLocalLyrics: () -> Unit = {},
    onClearMetadataCache: () -> Unit = {},
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deviceAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportSafAudio(uris, false)
    }

    val cloudAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportSafAudio(uris, true)
    }

    val downloadedCount = tracks.count { it.isDownloaded }
    val deviceCount = tracks.count { it.sourceType == TrackSourceType.LOCAL_SAF }
    val cloudCount = tracks.count { it.sourceType == TrackSourceType.CLOUD_SAF }
    val localTracks = tracks.filter {
        it.sourceType == TrackSourceType.LOCAL_SAF || it.sourceType == TrackSourceType.CLOUD_SAF || it.isDownloaded
    }
    val localWithLyricsCount = localTracks.count {
        it.syncedLrcText.isNotBlank() || it.plainLyrics.isNotBlank()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PulseWaveBrandLogo(size = 38.dp)
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = "Appearance, offline vault, audio sources & account",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 1. Account & Sync Summary
        item {
            ObsidianGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                accentTint = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isSignedIn) {
                                    Text(
                                        text = currentUserProfile.displayName.take(1).uppercase().ifBlank { "P" },
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.PersonOutline,
                                        contentDescription = "Guest listener",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isSignedIn) currentUserProfile.displayName else "Guest Mode",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isSignedIn) {
                                    "@${currentUserProfile.handle} · $syncedCount cloud synced"
                                } else {
                                    "Local vault & streaming · Cloud features locked"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    if (isSignedIn) {
                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Sign Out")
                        }
                    } else {
                        Button(
                            onClick = onSignInWithGoogle,
                            enabled = !isSigningIn,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("settings_sign_in_button")
                        ) {
                            if (isSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Signing in...")
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        GoogleGLogo(modifier = Modifier.size(14.dp))
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Sign In", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // 2. Playback & Offline Mode
        item {
            ObsidianGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                accentTint = if (settings.offlineMode) MintElectric else UltravioletPulse
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Playback & Offline",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OfflinePin,
                                contentDescription = null,
                                tint = if (settings.offlineMode) MintElectric else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text("Offline Mode", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (settings.offlineMode) {
                                        "Playing downloaded, device & cloud-cached audio only"
                                    } else {
                                        "Online streaming + offline vault active"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.offlineMode,
                            onCheckedChange = onToggleOfflineMode,
                            modifier = Modifier.testTag("offline_mode_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.HighQuality,
                                contentDescription = null,
                                tint = if (settings.highResStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text("High-Quality Audio Streaming", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Prefer highest available stream bitrate",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.highResStreaming,
                            onCheckedChange = onToggleHighResStreaming,
                            modifier = Modifier.testTag("high_res_streaming_switch")
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    var crossfadeSliderValue by remember(settings.crossfadeSeconds) {
                        mutableFloatStateOf(settings.crossfadeSeconds.toFloat())
                    }
                    val displaySeconds = crossfadeSliderValue.roundToInt().coerceIn(0, 12)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = if (displaySeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column {
                                    Text("Crossfade Duration", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = if (displaySeconds == 0) {
                                            "Gapless track transitions without overlap"
                                        } else {
                                            "Smooth ${displaySeconds}s fade between consecutive songs"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (displaySeconds > 0) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (displaySeconds == 0) "Off" else "${displaySeconds}s",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (displaySeconds > 0) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .testTag("crossfade_duration_value")
                                )
                            }
                        }

                        Slider(
                            value = crossfadeSliderValue,
                            onValueChange = { crossfadeSliderValue = it },
                            onValueChangeFinished = {
                                onCrossfadeSecondsChange(crossfadeSliderValue.roundToInt().coerceIn(0, 12))
                            },
                            valueRange = 0f..12f,
                            steps = 11,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Crossfade duration, currently $displaySeconds seconds"
                                }
                                .testTag("crossfade_duration_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("Off", "4s", "8s", "12s").forEach { tick ->
                                Text(
                                    text = tick,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 5-Band Equalizer & Audio DSP
        item {
            EqualizerControlPanel(
                eqEnabled = eqEnabled,
                activePreset = activeEqPreset,
                bandLevels = eqBandLevels,
                bassBoost = eqBassBoost,
                reverbPreset = eqReverbPreset,
                onToggleEqEnabled = onToggleEqEnabled,
                onSelectPreset = onSelectEqPreset,
                onUpdateBand = onUpdateEqBand,
                onUpdateBassBoost = onUpdateEqBassBoost,
                onUpdateReverbPreset = onUpdateEqReverb,
                onResetEqualizer = onResetEqualizer
            )
        }

        // 4. Sleep Timer
        item {
            val isTimerActive = sleepTimerRemainingMs != null && sleepTimerRemainingMs > 0L
            val presetMinutes = listOf(15, 30, 45, 60)
            var showCustomSlider by remember { mutableStateOf(false) }
            var customMinutesSlider by remember { mutableFloatStateOf(20f) }
            val selectedCustomMinutes = customMinutesSlider.roundToInt().coerceIn(5, 120)

            ObsidianGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                accentTint = if (isTimerActive) MintElectric else UltravioletPulse
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bedtime,
                                contentDescription = null,
                                tint = if (isTimerActive) MintElectric else MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    text = if (isTimerActive) {
                                        "Playback will gently pause when countdown ends"
                                    } else {
                                        "Automatically stop playback after a set duration"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTimerActive) {
                                MintElectric.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = if (isTimerActive) {
                                    formatCountdown(sleepTimerRemainingMs!!)
                                } else {
                                    "Off"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isTimerActive) {
                                    MintElectric
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("sleep_timer_status_badge")
                            )
                        }
                    }

                    AnimatedVisibility(visible = isTimerActive) {
                        val total = (sleepTimerTotalMs ?: sleepTimerRemainingMs ?: 1L).coerceAtLeast(1L)
                        val remaining = (sleepTimerRemainingMs ?: 0L).coerceIn(0L, total)
                        val progress = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MintElectric,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onExtendSleepTimer(5) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("sleep_timer_extend_button")
                                ) {
                                    Text("+5 min", maxLines = 1)
                                }
                                Button(
                                    onClick = onCancelSleepTimer,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("sleep_timer_cancel_button")
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Stop Timer", maxLines = 1)
                                }
                            }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presetMinutes) { mins ->
                            val isApproxSelected = isTimerActive &&
                                sleepTimerTotalMs == mins * 60_000L
                            FilterChip(
                                selected = isApproxSelected,
                                onClick = {
                                    showCustomSlider = false
                                    onStartSleepTimer(mins)
                                },
                                label = { Text("${mins}m") },
                                modifier = Modifier.testTag("sleep_timer_preset_${mins}m")
                            )
                        }
                        item {
                            FilterChip(
                                selected = showCustomSlider,
                                onClick = { showCustomSlider = !showCustomSlider },
                                label = { Text("Custom") },
                                modifier = Modifier.testTag("sleep_timer_custom_chip")
                            )
                        }
                    }

                    AnimatedVisibility(visible = showCustomSlider) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Custom Duration",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "$selectedCustomMinutes min",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = customMinutesSlider,
                                onValueChange = { customMinutesSlider = it },
                                valueRange = 5f..120f,
                                steps = 22,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = "Custom sleep timer duration, $selectedCustomMinutes minutes"
                                    }
                                    .testTag("sleep_timer_custom_slider")
                            )
                            Button(
                                onClick = {
                                    onStartSleepTimer(selectedCustomMinutes)
                                    showCustomSlider = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("sleep_timer_start_custom_button")
                            ) {
                                Text("Start ${selectedCustomMinutes}-Minute Timer")
                            }
                        }
                    }
                }
            }
        }

        // 4. Appearance & Theme
        item {
            ObsidianGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                accentTint = UltravioletPulse
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Appearance & Theme", style = MaterialTheme.typography.titleLarge)
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ThemeMode.entries) { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onSelectThemeMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = if (settings.dynamicAlbumGlow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text("Dynamic Album Glow", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Tints UI aura from active cover art",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.dynamicAlbumGlow,
                            onCheckedChange = onToggleDynamicGlow,
                            modifier = Modifier.testTag("dynamic_glow_switch")
                        )
                    }
                }
            }
        }

        // 4. Audio Sources & Cloud Providers
        item {
            ObsidianGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                accentTint = MintElectric
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Audio Sources & Cloud Sync",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "$downloadedCount downloaded · $deviceCount device · $cloudCount cloud drive",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val cacheMb = offlineCacheSummary.totalBytesUsed / (1024f * 1024f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Room Cache: ${offlineCacheSummary.cachedTrackCount} songs cached · " +
                                "${offlineCacheSummary.downloadedCount} offline (${"%.1f".format(cacheMb)} MB)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("room_cache_summary_text")
                        )
                    }
                    Text(
                        text = "Synchronized lyrics cached for $localWithLyricsCount of ${localTracks.size} local/offline tracks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { deviceAudioLauncher.launch(arrayOf("audio/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("import_device_audio_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Device Audio", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = {
                                if (isSignedIn) {
                                    cloudAudioLauncher.launch(arrayOf("audio/*"))
                                } else {
                                    onRequireSignIn("Cloud Drive sync")
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("sync_cloud_drive_button")
                        ) {
                            Icon(
                                imageVector = if (isSignedIn) Icons.Rounded.CloudSync else Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Cloud Drive", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!isSignedIn) {
                        Text(
                            text = "Cloud Drive import & cross-device library sync require Google Sign-In.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = onSyncLocalLyrics,
                        enabled = !isSyncingLocalLyrics && !settings.offlineMode && localTracks.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("sync_local_lyrics_button")
                    ) {
                        if (isSyncingLocalLyrics) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching Local Metadata & Lyrics...")
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Fetch Lyrics for Device Songs")
                        }
                    }

                    val hasClearableMetadata = offlineCacheSummary.cachedTrackCount >
                        (offlineCacheSummary.downloadedCount + offlineCacheSummary.localImportedCount)
                    OutlinedButton(
                        onClick = onClearMetadataCache,
                        enabled = hasClearableMetadata,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("clear_metadata_cache_button")
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Cached Stream Metadata")
                    }
                }
            }
        }
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
