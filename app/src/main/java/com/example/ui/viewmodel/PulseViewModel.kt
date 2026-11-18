package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ListeningSignalEntity
import com.example.data.local.LocalLibraryRepository
import com.example.data.local.OfflineCacheSummary
import com.example.data.local.PreferencesRepository
import com.example.data.local.UserAppSettings
import com.example.data.model.DiscoveryFeedBundle
import com.example.data.model.FeedPostDoc
import com.example.data.model.LyricLine
import com.example.data.model.SonicDnaProfile
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.remote.AudiusCatalogClient
import com.example.data.remote.LocalTrackMetadataResolver
import com.example.data.remote.LrclibClient
import com.example.data.remote.LyricsPayload
import com.example.data.remote.OfflineDownloadManager
import com.example.domain.AlbumColorExtractor
import com.example.domain.RecommendationEngine
import com.example.domain.SafMusicImporter
import com.example.media.PlaybackController
import com.example.media.PlaybackUiState
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class PulseViewModel(
    private val appContext: Context,
    private val libraryRepo: LocalLibraryRepository,
    private val prefsRepo: PreferencesRepository,
    private val audiusClient: AudiusCatalogClient,
    private val lrclibClient: LrclibClient,
    private val downloadManager: OfflineDownloadManager,
    private val safImporter: SafMusicImporter,
    private val recommendationEngine: RecommendationEngine,
    val playbackController: PlaybackController,
    private val metadataResolver: LocalTrackMetadataResolver = LocalTrackMetadataResolver()
) : ViewModel() {

    val settings: StateFlow<UserAppSettings> = prefsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserAppSettings())

    val playbackState: StateFlow<PlaybackUiState> = playbackController.state

    val downloadProgress: StateFlow<Map<String, Float>> = downloadManager.downloadProgress

    private val _remoteCatalog = MutableStateFlow<List<Track>>(emptyList())

    val offlineCacheSummary: StateFlow<OfflineCacheSummary> = libraryRepo.cacheSummary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), OfflineCacheSummary())

    val allLibraryTracks: StateFlow<List<Track>> = combine(
        libraryRepo.allTracks,
        _remoteCatalog,
        settings
    ) { localTracks, remoteTracks, currentSettings ->
        val localMap = localTracks.associateBy { it.id }
        if (currentSettings.offlineMode) {
            localMap.values.filter {
                it.isDownloaded ||
                    it.sourceType == TrackSourceType.LOCAL_SAF ||
                    it.sourceType == TrackSourceType.CLOUD_SAF
            }
        } else {
            val merged = LinkedHashMap<String, Track>()
            localMap.values.forEach { merged[it.id] = it }
            remoteTracks.forEach { r ->
                val existing = merged[r.id]
                if (existing == null) merged[r.id] = r
            }
            merged.values.toList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val sonicDnaProfile: StateFlow<SonicDnaProfile> = combine(
        libraryRepo.recentSignals,
        allLibraryTracks
    ) { signals, tracks ->
        recommendationEngine.computeSonicDna(signals, tracks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), SonicDnaProfile())

    private val _discoveryState = MutableStateFlow<UiState<DiscoveryFeedBundle>>(UiState.Loading)
    val discoveryState: StateFlow<UiState<DiscoveryFeedBundle>> = _discoveryState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _parsedLyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val parsedLyrics: StateFlow<List<LyricLine>> = _parsedLyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    private val _isSyncingLocalLyrics = MutableStateFlow(false)
    val isSyncingLocalLyrics: StateFlow<Boolean> = _isSyncingLocalLyrics.asStateFlow()

    private val _aiTrackStory = MutableStateFlow<UiState<String>?>(null)
    val aiTrackStory: StateFlow<UiState<String>?> = _aiTrackStory.asStateFlow()

    private val _albumAccent = MutableStateFlow<Color?>(null)
    val albumAccent: StateFlow<Color?> = _albumAccent.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var lastLoadedTrackId: String? = null
    private var lastMoodPrompt: String? = null
    private var latestFriendPosts: List<FeedPostDoc> = emptyList()

    private var hasRestoredEqFromPrefs = false

    init {
        viewModelScope.launch {
            libraryRepo.verifyOfflineCacheIntegrity()
            refreshCatalogAndRecommendations()
        }
        observeActiveTrackChanges()
        observePlaybackPreferences()
        backfillMissingLocalArtwork()
    }

    private fun observePlaybackPreferences() {
        viewModelScope.launch {
            prefsRepo.settingsFlow.collect { current ->
                playbackController.setCrossfadeSeconds(current.crossfadeSeconds)
                if (!hasRestoredEqFromPrefs) {
                    hasRestoredEqFromPrefs = true
                    playbackController.restoreEqualizerState(
                        enabled = current.eqEnabled,
                        soundscapeId = current.activeSoundscapeId,
                        bands = current.eqBandLevels,
                        bass = current.eqBassBoost,
                        reverb = current.eqReverbPreset
                    )
                }
            }
        }
    }

    private fun observeActiveTrackChanges() {
        viewModelScope.launch {
            playbackState.collect { state ->
                val track = state.currentTrack ?: return@collect
                if (track.id != lastLoadedTrackId) {
                    lastLoadedTrackId = track.id
                    loadLyricsForTrack(track)
                    extractAccentForTrack(track)
                }
            }
        }
    }

    fun updateFriendPostsContext(posts: List<FeedPostDoc>) {
        latestFriendPosts = posts
    }

    fun refreshCatalogAndRecommendations(moodPrompt: String? = lastMoodPrompt) {
        lastMoodPrompt = moodPrompt
        viewModelScope.launch {
            _discoveryState.value = UiState.Loading
            try {
                val offline = settings.value.offlineMode
                if (!offline) {
                    val fetched = try {
                        audiusClient.fetchTrending(limit = 32)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("PulseVM", "Audius fetch error: ${e.message}")
                        emptyList()
                    }
                    if (fetched.isNotEmpty()) {
                        _remoteCatalog.value = fetched
                        libraryRepo.cacheRemoteCatalog(fetched)
                    }
                }

                val signals = libraryRepo.getRecentSignals()
                val candidates = allLibraryTracks.value.ifEmpty {
                    if (offline) libraryRepo.getOfflinePlayableSnapshot()
                    else _remoteCatalog.value.ifEmpty { libraryRepo.getOfflinePlayableSnapshot() }
                }
                val bundle = recommendationEngine.buildRecommendations(
                    candidates = candidates,
                    signals = signals,
                    friendFeedPosts = latestFriendPosts,
                    moodPrompt = moodPrompt,
                    offlineMode = offline
                )
                _discoveryState.value = UiState.Success(bundle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _discoveryState.value = UiState.Error(e.localizedMessage ?: "Failed to build recommendations")
            }
        }
    }

    fun searchTracks(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val isOffline = settings.value.offlineMode
                val localMatches = allLibraryTracks.value.filter {
                    it.title.contains(trimmed, true) ||
                        it.artist.contains(trimmed, true) ||
                        it.genre.contains(trimmed, true)
                }
                val roomMatches = libraryRepo.searchCachedTracks(trimmed).let { cached ->
                    if (isOffline) {
                        cached.filter {
                            it.isDownloaded ||
                                it.sourceType == TrackSourceType.LOCAL_SAF ||
                                it.sourceType == TrackSourceType.CLOUD_SAF
                        }
                    } else cached
                }
                val remoteMatches = if (!isOffline) {
                    try {
                        audiusClient.search(trimmed, limit = 20)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else emptyList()
                _searchResults.value = (localMatches + roomMatches + remoteMatches).distinctBy { it.id }
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun playTrack(track: Track, queue: List<Track> = allLibraryTracks.value) {
        viewModelScope.launch {
            val persisted = libraryRepo.recordTrackPlayed(track)
            val resolvedQueue = queue.ifEmpty { listOf(persisted) }.map { item ->
                if (item.id == persisted.id) persisted else item
            }
            playbackController.playTrack(persisted, resolvedQueue)
        }
    }

    fun recordFeedCardDwell(track: Track, dwellMs: Long) {
        if (dwellMs < 2_500L) return
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepo.insertSignal(
                ListeningSignalEntity(
                    trackId = track.id,
                    genre = track.genre,
                    mood = track.mood,
                    bpm = track.bpm,
                    completionRatio = 0.5f,
                    earlySkip = false,
                    replayCount = 0,
                    seekBackCount = 0,
                    dwellMs = dwellMs,
                    liked = track.isLiked,
                    savedOffline = track.isDownloaded,
                    sharedInDm = false,
                    timeBucket = recommendationEngine.currentTimeBucket()
                )
            )
        }
    }

    fun toggleLikeTrack(track: Track) {
        viewModelScope.launch {
            val nextLiked = !track.isLiked
            val updated = libraryRepo.setLiked(track, nextLiked)
            if (nextLiked) {
                libraryRepo.insertSignal(
                    ListeningSignalEntity(
                        trackId = track.id,
                        genre = track.genre,
                        mood = track.mood,
                        bpm = track.bpm,
                        completionRatio = 1.0f,
                        earlySkip = false,
                        replayCount = 1,
                        seekBackCount = 0,
                        dwellMs = 15_000L,
                        liked = true,
                        savedOffline = track.isDownloaded,
                        sharedInDm = false,
                        timeBucket = recommendationEngine.currentTimeBucket()
                    )
                )
            }
            _remoteCatalog.update { list ->
                list.map { if (it.id == track.id) updated else it }
            }
        }
    }

    fun downloadTrackOffline(track: Track) {
        viewModelScope.launch {
            _statusMessage.value = "Downloading ${track.title} for offline listening..."
            val res = downloadManager.downloadTrack(track)
            res.onSuccess {
                _statusMessage.value = "Saved ${track.title} offline"
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Download failed"
            }
        }
    }

    fun removeOfflineTrack(track: Track) {
        viewModelScope.launch {
            downloadManager.removeOfflineDownload(track)
            _statusMessage.value = "Removed offline file for ${track.title}"
        }
    }

    fun importSafAudio(uris: List<Uri>, isCloudProvider: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = safImporter.importAudioUris(uris, isCloudProvider)
            if (imported.isNotEmpty()) {
                val enriched = if (!settings.value.offlineMode) {
                    imported.map { enrichLocalTrackMetadataAndLyrics(it) }
                } else imported
                libraryRepo.upsertAll(enriched)
                val withLyricsCount = enriched.count { it.syncedLrcText.isNotBlank() || it.plainLyrics.isNotBlank() }
                _statusMessage.value = if (withLyricsCount > 0) {
                    "Imported ${enriched.size} track(s) · synced lyrics fetched for $withLyricsCount"
                } else {
                    "Imported ${enriched.size} ${if (isCloudProvider) "cloud" else "device"} track(s)"
                }
                refreshCatalogAndRecommendations()
            } else {
                _statusMessage.value = "No readable audio files found in selection"
            }
        }
    }

    fun syncLocalTracksMetadataAndLyrics() {
        if (_isSyncingLocalLyrics.value) return
        if (settings.value.offlineMode) {
            _statusMessage.value = "Turn off Offline Mode to fetch metadata & lyrics"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingLocalLyrics.value = true
            try {
                val tracks = libraryRepo.getOfflinePlayableSnapshot()
                if (tracks.isEmpty()) {
                    _statusMessage.value = "No device or offline tracks to sync"
                    return@launch
                }
                var updatedLyricsCount = 0
                tracks.forEach { before ->
                    val beforeHasLyrics = before.syncedLrcText.isNotBlank() || before.plainLyrics.isNotBlank()
                    val beforeArt = before.artworkUrl.isNotBlank()
                    val enriched = enrichLocalTrackMetadataAndLyrics(before)
                    val afterHasLyrics = enriched.syncedLrcText.isNotBlank() || enriched.plainLyrics.isNotBlank()
                    val changed = enriched != before
                    if (changed) {
                        libraryRepo.upsertTrack(enriched)
                        if (!beforeHasLyrics && afterHasLyrics) updatedLyricsCount++
                        if (playbackState.value.currentTrack?.id == enriched.id) {
                            playbackController.updateTrackMetadata(enriched)
                            if (!beforeArt && enriched.artworkUrl.isNotBlank()) {
                                extractAccentForTrack(enriched)
                            }
                            if (_parsedLyrics.value.isEmpty() && afterHasLyrics) {
                                _parsedLyrics.value = LrclibClient.toDisplayLyrics(
                                    enriched.syncedLrcText,
                                    enriched.plainLyrics,
                                    enriched.durationMs
                                )
                            }
                        }
                    }
                }
                _statusMessage.value = if (updatedLyricsCount > 0) {
                    "Fetched synchronized lyrics for $updatedLyricsCount local track(s)"
                } else {
                    "Local device metadata & lyrics are up to date"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _statusMessage.value = "Could not complete local lyrics sync"
            } finally {
                _isSyncingLocalLyrics.value = false
            }
        }
    }

    private suspend fun enrichLocalTrackMetadataAndLyrics(track: Track): Track {
        var current = track
        if (current.artworkUrl.isBlank() && current.localUri.isNotBlank()) {
            val embedded = safImporter.resolveEmbeddedArtwork(current.id, current.localUri)
            if (embedded.isNotBlank()) {
                current = current.copy(artworkUrl = embedded)
            }
        }

        val isLocalSource = current.sourceType == TrackSourceType.LOCAL_SAF || current.sourceType == TrackSourceType.CLOUD_SAF
        val hasPlaceholderArtist = current.artist.equals("Local Device", true) ||
            current.artist.equals("Cloud Drive", true) ||
            current.artist.isBlank()

        var resolvedMeta = if (isLocalSource && (hasPlaceholderArtist || current.artworkUrl.isBlank())) {
            metadataResolver.resolveMetadata(current.title, current.artist, current.durationMs)
        } else null

        if (resolvedMeta != null) {
            current = current.copy(
                title = if (hasPlaceholderArtist && resolvedMeta.title.isNotBlank()) resolvedMeta.title else current.title,
                artist = if (hasPlaceholderArtist && resolvedMeta.artist.isNotBlank()) resolvedMeta.artist else current.artist,
                album = if ((current.album.equals("On Device", true) || current.album.equals("Cloud Synced", true)) && resolvedMeta.album.isNotBlank()) resolvedMeta.album else current.album,
                genre = if (current.genre.equals("Imported", true) && resolvedMeta.genre.isNotBlank()) resolvedMeta.genre else current.genre,
                artworkUrl = current.artworkUrl.ifBlank { resolvedMeta.artworkUrl }
            )
        }

        if (current.artworkUrl.isBlank()) {
            val onlineArt = audiusClient.lookupArtworkUrl(current.title, current.artist)
            if (!onlineArt.isNullOrBlank()) current = current.copy(artworkUrl = onlineArt)
        }

        if (current.syncedLrcText.isBlank() && current.plainLyrics.isBlank()) {
            var lyrics = lrclibClient.fetchLyrics(current.title, current.artist, current.durationMs, current.album)
            if (lyrics.syncedLrc.isBlank() && lyrics.plainLyrics.isBlank() && resolvedMeta == null) {
                resolvedMeta = metadataResolver.resolveMetadata(current.title, current.artist, current.durationMs)
                if (resolvedMeta != null) {
                    lyrics = lrclibClient.fetchLyrics(
                        resolvedMeta.title,
                        resolvedMeta.artist,
                        resolvedMeta.durationMs.takeIf { it > 0L } ?: current.durationMs,
                        resolvedMeta.album
                    )
                    if (current.artworkUrl.isBlank() && resolvedMeta.artworkUrl.isNotBlank()) {
                        current = current.copy(artworkUrl = resolvedMeta.artworkUrl)
                    }
                }
            }
            if (lyrics.syncedLrc.isNotBlank() || lyrics.plainLyrics.isNotBlank()) {
                current = current.copy(
                    syncedLrcText = lyrics.syncedLrc,
                    plainLyrics = lyrics.plainLyrics
                )
            }
        }
        return current
    }

    fun refreshLyricsForTrack(
        track: Track,
        customTitle: String? = null,
        customArtist: String? = null
    ) {
        loadLyricsForTrack(
            track = track,
            overrideTitle = customTitle?.trim()?.takeIf { it.isNotBlank() },
            overrideArtist = customArtist?.trim(),
            forceNetwork = true
        )
    }

    private fun loadLyricsForTrack(
        track: Track,
        overrideTitle: String? = null,
        overrideArtist: String? = null,
        forceNetwork: Boolean = false
    ) {
        viewModelScope.launch {
            _aiTrackStory.value = null
            if (!forceNetwork) {
                val cachedTrack = libraryRepo.getTrackById(track.id)
                val synced = track.syncedLrcText.ifBlank { cachedTrack?.syncedLrcText.orEmpty() }
                val plain = track.plainLyrics.ifBlank { cachedTrack?.plainLyrics.orEmpty() }
                if (synced.isNotBlank() || plain.isNotBlank()) {
                    _parsedLyrics.value = LrclibClient.toDisplayLyrics(synced, plain, track.durationMs)
                    return@launch
                }
            }

            if (settings.value.offlineMode) {
                _parsedLyrics.value = emptyList()
                if (forceNetwork) {
                    _statusMessage.value = "Turn off Offline Mode to fetch lyrics online"
                }
                return@launch
            }

            _isLoadingLyrics.value = true
            try {
                val queryTitle = overrideTitle ?: track.title
                val queryArtist = overrideArtist ?: track.artist
                var fetched: LyricsPayload = lrclibClient.fetchLyrics(
                    title = queryTitle,
                    artist = queryArtist,
                    durationMs = track.durationMs,
                    albumName = track.album
                )

                var enrichedTrack = track
                if (fetched.syncedLrc.isBlank() && fetched.plainLyrics.isBlank()) {
                    val resolvedMeta = metadataResolver.resolveMetadata(queryTitle, queryArtist, track.durationMs)
                    if (resolvedMeta != null) {
                        fetched = lrclibClient.fetchLyrics(
                            title = resolvedMeta.title,
                            artist = resolvedMeta.artist,
                            durationMs = resolvedMeta.durationMs.takeIf { it > 0L } ?: track.durationMs,
                            albumName = resolvedMeta.album
                        )
                        val isPlaceholderArtist = enrichedTrack.artist.equals("Local Device", true) ||
                            enrichedTrack.artist.equals("Cloud Drive", true)
                        if (isPlaceholderArtist || enrichedTrack.artworkUrl.isBlank()) {
                            enrichedTrack = enrichedTrack.copy(
                                artist = if (isPlaceholderArtist && resolvedMeta.artist.isNotBlank()) resolvedMeta.artist else enrichedTrack.artist,
                                artworkUrl = enrichedTrack.artworkUrl.ifBlank { resolvedMeta.artworkUrl }
                            )
                            libraryRepo.upsertTrack(enrichedTrack)
                        }
                    }
                }

                if (fetched.syncedLrc.isNotBlank() || fetched.plainLyrics.isNotBlank()) {
                    _parsedLyrics.value = LrclibClient.toDisplayLyrics(
                        fetched.syncedLrc,
                        fetched.plainLyrics,
                        enrichedTrack.durationMs
                    )
                    libraryRepo.updateLyrics(enrichedTrack.id, fetched.syncedLrc, fetched.plainLyrics)
                    val updatedTrack = enrichedTrack.copy(
                        syncedLrcText = fetched.syncedLrc,
                        plainLyrics = fetched.plainLyrics
                    )
                    playbackController.updateTrackMetadata(updatedTrack)
                    _remoteCatalog.update { list -> list.map { if (it.id == enrichedTrack.id) updatedTrack else it } }
                } else {
                    _parsedLyrics.value = emptyList()
                    if (forceNetwork) {
                        _statusMessage.value = "No lyrics found for \"$queryTitle\""
                    }
                }
            } finally {
                _isLoadingLyrics.value = false
            }
        }
    }

    fun generateAiVibeStory(track: Track) {
        viewModelScope.launch {
            _aiTrackStory.value = UiState.Loading
            val res = recommendationEngine.generateTrackVibeStory(track)
            _aiTrackStory.value = res.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.localizedMessage ?: "AI story unavailable") }
            )
        }
    }

    private fun backfillMissingLocalArtwork() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tracks = libraryRepo.getOfflinePlayableSnapshot()
                tracks.forEach { before ->
                    val enriched = if (!settings.value.offlineMode) {
                        enrichLocalTrackMetadataAndLyrics(before)
                    } else {
                        val art = if (before.artworkUrl.isBlank()) {
                            safImporter.resolveEmbeddedArtwork(before.id, before.localUri)
                        } else before.artworkUrl
                        before.copy(artworkUrl = art)
                    }
                    if (enriched != before) {
                        libraryRepo.upsertTrack(enriched)
                        if (playbackState.value.currentTrack?.id == before.id) {
                            playbackController.updateTrackMetadata(enriched)
                            if (before.displayArtworkUri.isBlank() && enriched.displayArtworkUri.isNotBlank()) {
                                extractAccentForTrack(enriched)
                            }
                            if (_parsedLyrics.value.isEmpty() &&
                                (enriched.syncedLrcText.isNotBlank() || enriched.plainLyrics.isNotBlank())
                            ) {
                                _parsedLyrics.value = LrclibClient.toDisplayLyrics(
                                    enriched.syncedLrcText,
                                    enriched.plainLyrics,
                                    enriched.durationMs
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun extractAccentForTrack(track: Track) {
        viewModelScope.launch {
            if (!settings.value.dynamicAlbumGlow) {
                _albumAccent.value = null
                return@launch
            }

            var activeTrack = track
            if (activeTrack.displayArtworkUri.isBlank()) {
                // Immediately apply vivid sonic accent so device songs transition smoothly right away
                _albumAccent.value = AlbumColorExtractor.deriveTrackAccentFallback(activeTrack)

                var resolvedArt = if (activeTrack.localUri.isNotBlank()) {
                    safImporter.resolveEmbeddedArtwork(activeTrack.id, activeTrack.localUri)
                } else ""
                if (resolvedArt.isBlank() && !settings.value.offlineMode) {
                    resolvedArt = audiusClient.lookupArtworkUrl(activeTrack.title, activeTrack.artist).orEmpty()
                }
                if (resolvedArt.isNotBlank()) {
                    libraryRepo.updateArtwork(activeTrack.id, resolvedArt)
                    activeTrack = activeTrack.copy(artworkUrl = resolvedArt)
                    playbackController.updateTrackMetadata(activeTrack)
                }
            }

            val extracted = if (activeTrack.displayArtworkUri.isNotBlank()) {
                AlbumColorExtractor.extractAccentColor(appContext, activeTrack.displayArtworkUri)
            } else null

            _albumAccent.value = extracted ?: AlbumColorExtractor.deriveTrackAccentFallback(activeTrack)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    }

    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setOfflineMode(enabled)
            refreshCatalogAndRecommendations()
        }
    }

    fun setDynamicAlbumGlow(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setDynamicAlbumGlow(enabled)
            if (!enabled) _albumAccent.value = null
            else playbackState.value.currentTrack?.let { extractAccentForTrack(it) }
        }
    }

    fun setHighResStreaming(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setHighResStreaming(enabled) }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        playbackController.setCrossfadeSeconds(clamped)
        viewModelScope.launch { prefsRepo.setCrossfadeSeconds(clamped) }
    }

    fun startSleepTimer(minutes: Int) {
        playbackController.startSleepTimer(minutes)
        _statusMessage.value = if (minutes > 0) {
            "Sleep timer set for $minutes min"
        } else {
            "Sleep timer turned off"
        }
    }

    fun extendSleepTimer(extraMinutes: Int = 5) {
        playbackController.extendSleepTimer(extraMinutes)
        _statusMessage.value = "Added $extraMinutes min to sleep timer"
    }

    fun cancelSleepTimer() {
        playbackController.cancelSleepTimer()
        _statusMessage.value = "Sleep timer cancelled"
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        playbackController.setEqualizerEnabled(enabled)
        persistCurrentEqualizerState()
    }

    fun applySoundscapePreset(preset: SoundscapePreset) {
        playbackController.applySoundscapePreset(preset)
        persistCurrentEqualizerState()
    }

    fun updateEqualizerBand(index: Int, millibel: Int) {
        playbackController.updateCustomBand(index, millibel)
        persistCurrentEqualizerState()
    }

    fun updateBassBoost(strength: Int) {
        playbackController.updateBassBoost(strength)
        persistCurrentEqualizerState()
    }

    fun updateReverbPreset(preset: Int) {
        playbackController.updateReverbPreset(preset)
        persistCurrentEqualizerState()
    }

    fun resetEqualizer() {
        playbackController.resetEqualizer()
        persistCurrentEqualizerState()
        _statusMessage.value = "Equalizer reset to Flat Studio"
    }

    private fun persistCurrentEqualizerState() {
        val s = playbackController.state.value
        viewModelScope.launch {
            prefsRepo.setEqualizerState(
                enabled = s.eqEnabled,
                soundscapeId = s.activeSoundscape.id,
                bandLevels = s.bandLevels,
                bassBoost = s.bassBoost,
                reverbPreset = s.reverbPreset
            )
        }
    }

    fun clearMetadataCache() {
        viewModelScope.launch {
            val removed = libraryRepo.clearRemoteMetadataCache()
            _statusMessage.value = if (removed > 0) {
                "Cleared $removed cached metadata entries (offline files kept)"
            } else {
                "Metadata cache is already clean"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.release()
    }
}
