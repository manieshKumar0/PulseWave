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
import com.example.data.remote.YouTubeMusicClient
import com.example.domain.AlbumColorExtractor
import com.example.domain.RecommendationEngine
import com.example.domain.SafMusicImporter
import com.example.media.PlaybackController
import com.example.media.PlaybackUiState
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private val metadataResolver: LocalTrackMetadataResolver = LocalTrackMetadataResolver(),
    private val youTubeClient: YouTubeMusicClient = YouTubeMusicClient.shared
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

    private val _isScanningDeviceMusic = MutableStateFlow(false)
    val isScanningDeviceMusic: StateFlow<Boolean> = _isScanningDeviceMusic.asStateFlow()

    private val _aiTrackStory = MutableStateFlow<UiState<String>?>(null)
    val aiTrackStory: StateFlow<UiState<String>?> = _aiTrackStory.asStateFlow()

    private val _albumAccent = MutableStateFlow<Color?>(null)
    val albumAccent: StateFlow<Color?> = _albumAccent.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _activeMoodMix = MutableStateFlow<String?>(null)
    val activeMoodMix: StateFlow<String?> = _activeMoodMix.asStateFlow()

    private var lastLoadedTrackId: String? = null
    private var lastReportedError: String? = null
    private var lastMoodPrompt: String? = null
    private var latestFriendPosts: List<FeedPostDoc> = emptyList()
    private var searchJob: Job? = null
    private var likedRecsJob: Job? = null
    private var moodMixJob: Job? = null

    private var hasRestoredEqFromPrefs = false

    init {
        viewModelScope.launch {
            libraryRepo.verifyOfflineCacheIntegrity()
            if (SafMusicImporter.hasDeviceAudioPermission(appContext)) {
                scanDeviceAudioTracks(silentIfNoPermission = true)
            }
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
                youTubeClient.preferHighQuality = current.highResStreaming
                youTubeClient.setYouTubeAuthSession(
                    cookie = current.youtubeCookie,
                    visitorData = current.youtubeVisitorData
                )
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

    fun saveYouTubeSession(cookie: String, visitorData: String, accountLabel: String) {
        viewModelScope.launch {
            prefsRepo.setYouTubeSession(cookie, visitorData, accountLabel)
            youTubeClient.setYouTubeAuthSession(cookie, visitorData)
            _statusMessage.value = "YouTube account connected for streaming"
            if (playbackController.state.value.playbackError != null) {
                playbackController.retryActiveTrack()
            }
        }
    }

    fun disconnectYouTubeSession() {
        viewModelScope.launch {
            runCatching {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
            }
            prefsRepo.clearYouTubeSession()
            youTubeClient.setYouTubeAuthSession("", "")
            _statusMessage.value = "YouTube playback session disconnected"
        }
    }

    private fun observeActiveTrackChanges() {
        viewModelScope.launch {
            playbackState.collect { state ->
                val err = state.playbackError
                if (!err.isNullOrBlank() && err != lastReportedError) {
                    lastReportedError = err
                    _statusMessage.value = err
                } else if (err.isNullOrBlank()) {
                    lastReportedError = null
                }

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

    fun refreshCatalogAndRecommendations(
        moodPrompt: String? = lastMoodPrompt,
        showLoading: Boolean = true
    ) {
        lastMoodPrompt = moodPrompt
        viewModelScope.launch {
            if (showLoading || _discoveryState.value !is UiState.Success) {
                _discoveryState.value = UiState.Loading
            }
            try {
                val offline = settings.value.offlineMode
                val signals = libraryRepo.getRecentSignals()
                val roomSnapshot = libraryRepo.getAllTracksSnapshot()
                val liveDna = recommendationEngine.computeSonicDna(signals, roomSnapshot)
                val likedTracks = collectLikedSeedTracks(roomSnapshot, signals)

                var fetchedLive = emptyList<Track>()
                var fetchedYtLikedRecs = emptyList<Track>()
                if (!offline) {
                    val preferredGenres = liveDna.topGenres
                    val recentArtists = (likedTracks + roomSnapshot
                        .filter { it.lastPlayedAt > 0L || it.isLiked }
                        .sortedByDescending { maxOf(it.lastPlayedAt, if (it.isLiked) 1L else 0L) })
                        .map { it.artist }
                        .filter {
                            it.isNotBlank() &&
                                !it.equals("YouTube Music", true) &&
                                !it.equals("Local Device", true) &&
                                !it.equals("Cloud Drive", true)
                        }
                        .distinct()
                        .take(3)

                    coroutineScope {
                        val ytDeferred = async {
                            try {
                                youTubeClient.fetchDiscoveryCatalog(
                                    moodPrompt = moodPrompt,
                                    preferredGenres = preferredGenres,
                                    recentArtists = recentArtists,
                                    limit = 24
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("PulseVM", "YouTube discovery error: ${e.message}")
                                emptyList()
                            }
                        }
                        val ytLikedDeferred = async {
                            try {
                                youTubeClient.fetchLikedBasedRecommendations(
                                    likedTracks = likedTracks,
                                    preferredGenres = preferredGenres,
                                    limit = 12
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("PulseVM", "YouTube liked recommendations error: ${e.message}")
                                emptyList()
                            }
                        }
                        val audiusDeferred = async {
                            try {
                                audiusClient.fetchTrending(limit = 12)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("PulseVM", "Audius fetch error: ${e.message}")
                                emptyList()
                            }
                        }
                        fetchedYtLikedRecs = ytLikedDeferred.await()
                            .filter { it.sourceType == TrackSourceType.YOUTUBE }
                            .distinctBy { it.id }
                        fetchedLive = (ytDeferred.await() + fetchedYtLikedRecs + audiusDeferred.await())
                            .distinctBy { it.id }
                    }
                    if (fetchedLive.isNotEmpty()) {
                        val roomById = roomSnapshot.associateBy { it.id }
                        val mergedLive = fetchedLive.map { mergeSavedFlags(it, roomById[it.id]) }
                        _remoteCatalog.value = mergedLive
                        libraryRepo.cacheRemoteCatalog(mergedLive)
                    }
                }

                val roomById = roomSnapshot.associateBy { it.id }
                val candidates = when {
                    offline -> libraryRepo.getOfflinePlayableSnapshot()
                    fetchedLive.isNotEmpty() ->
                        (fetchedLive.map { mergeSavedFlags(it, roomById[it.id]) } + roomSnapshot + allLibraryTracks.value)
                            .distinctBy { it.id }
                    else -> (roomSnapshot + allLibraryTracks.value + _remoteCatalog.value)
                        .distinctBy { it.id }
                        .ifEmpty { libraryRepo.getOfflinePlayableSnapshot() }
                }
                val bundle = recommendationEngine.buildRecommendations(
                    candidates = candidates,
                    signals = signals,
                    friendFeedPosts = latestFriendPosts,
                    moodPrompt = moodPrompt,
                    offlineMode = offline
                )
                val ytOnlyBento = (fetchedYtLikedRecs.map { mergeSavedFlags(it, roomById[it.id]) } +
                    (bundle.heroPicks + bundle.replayPicks + bundle.explorePicks).map { it.track }
                        .filter { it.sourceType == TrackSourceType.YOUTUBE })
                    .filter { it.sourceType == TrackSourceType.YOUTUBE }
                    .distinctBy { it.id }
                    .take(10)

                _discoveryState.value = UiState.Success(
                    bundle.copy(youtubeLikedRecs = ytOnlyBento)
                )

                if (!offline) {
                    val topYtIds = (ytOnlyBento.take(3) + bundle.heroPicks.map { it.track })
                        .mapNotNull { track ->
                            youTubeClient.registerTrackHint(track)
                            track.youtubeVideoId
                        }
                        .distinct()
                        .take(3)
                    if (topYtIds.isNotEmpty()) {
                        launch(Dispatchers.IO) {
                            runCatching {
                                youTubeClient.prewarmAudioStreams(topYtIds, maxConcurrent = 3)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _discoveryState.value = UiState.Error(e.localizedMessage ?: "Failed to build recommendations")
            }
        }
    }

    private fun collectLikedSeedTracks(
        roomSnapshot: List<Track>,
        signals: List<ListeningSignalEntity>
    ): List<Track> {
        val likedIdsFromSignals = signals.filter { it.liked }.map { it.trackId }.toSet()
        return (roomSnapshot + allLibraryTracks.value)
            .distinctBy { it.id }
            .filter { it.isLiked || it.id in likedIdsFromSignals }
            .sortedWith(
                compareByDescending<Track> { it.isLiked }
                    .thenByDescending { it.lastPlayedAt }
                    .thenByDescending { it.playCount }
            )
    }

    private fun refreshYouTubeLikedRecommendationsInBackground() {
        if (settings.value.offlineMode) return
        likedRecsJob?.cancel()
        likedRecsJob = viewModelScope.launch {
            delay(250L)
            runCatching {
                val roomSnapshot = libraryRepo.getAllTracksSnapshot()
                val signals = libraryRepo.getRecentSignals()
                val liveDna = recommendationEngine.computeSonicDna(signals, roomSnapshot)
                val likedTracks = collectLikedSeedTracks(roomSnapshot, signals)
                val roomById = roomSnapshot.associateBy { it.id }
                val freshYtRecs = youTubeClient.fetchLikedBasedRecommendations(
                    likedTracks = likedTracks,
                    preferredGenres = liveDna.topGenres,
                    limit = 12
                )
                    .filter { it.sourceType == TrackSourceType.YOUTUBE }
                    .distinctBy { it.id }
                    .map { mergeSavedFlags(it, roomById[it.id]) }

                if (freshYtRecs.isNotEmpty()) {
                    freshYtRecs.take(3).forEach { youTubeClient.registerTrackHint(it) }
                    _discoveryState.update { state ->
                        if (state is UiState.Success) {
                            state.copy(data = state.data.copy(youtubeLikedRecs = freshYtRecs))
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    fun searchTracks(query: String) {
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
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

                if (isOffline) {
                    _searchResults.value = (localMatches + roomMatches).distinctBy { it.id }
                    return@launch
                }

                // Publish local hits immediately while debouncing live YouTube Music + Audius queries
                _searchResults.value = (localMatches + roomMatches).distinctBy { it.id }
                delay(240L)

                val isDirectLink = YouTubeMusicClient.extractVideoId(trimmed) != null
                val (ytMatches, audiusMatches) = coroutineScope {
                    val ytDeferred = async {
                        try {
                            youTubeClient.searchMusic(trimmed, limit = 25)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("PulseVM", "YouTube search error: ${e.message}")
                            emptyList()
                        }
                    }
                    val audiusDeferred = async {
                        if (isDirectLink) {
                            emptyList()
                        } else {
                            try {
                                audiusClient.search(trimmed, limit = 12)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                    ytDeferred.await() to audiusDeferred.await()
                }

                val combined = if (isDirectLink) {
                    ytMatches + localMatches + roomMatches
                } else {
                    val downloadedFirst = (localMatches + roomMatches).filter {
                        it.isDownloaded ||
                            it.sourceType == TrackSourceType.LOCAL_SAF ||
                            it.sourceType == TrackSourceType.CLOUD_SAF
                    }
                    downloadedFirst + ytMatches + localMatches + roomMatches + audiusMatches
                }
                val savedById = allLibraryTracks.value.associateBy { it.id } +
                    libraryRepo.getAllTracksSnapshot().associateBy { it.id }
                val deduped = combined
                    .distinctBy { it.id }
                    .map { mergeSavedFlags(it, savedById[it.id]) }
                _searchResults.value = deduped

                // Prewarm top YouTube search hits in background so clicking plays near-instantly
                val topYtIds = ytMatches.mapNotNull { it.youtubeVideoId }.take(3)
                if (topYtIds.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            youTubeClient.prewarmAudioStreams(topYtIds, maxConcurrent = 3)
                        }
                    }
                }
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun playTrack(track: Track, queue: List<Track> = allLibraryTracks.value) {
        _activeMoodMix.value = null
        startPlaybackInternal(track, queue)
    }

    private fun startPlaybackInternal(track: Track, queue: List<Track>) {
        val safeQueue = queue.ifEmpty { listOf(track) }
        playbackController.playTrack(track, safeQueue)
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = libraryRepo.recordTrackPlayed(track)
            if (persisted != track) {
                syncTrackStateAcrossUi(persisted)
            }
        }
    }

    fun playMoodMix(
        moodTitle: String,
        searchQuery: String,
        toggleIfSame: Boolean = true
    ) {
        val cleanTitle = moodTitle.trim()
        val cleanQuery = searchQuery.trim().ifBlank { cleanTitle }
        if (cleanTitle.isBlank()) return

        if (toggleIfSame &&
            _activeMoodMix.value.equals(cleanTitle, ignoreCase = true) &&
            playbackState.value.currentTrack != null
        ) {
            playbackController.togglePlayPause()
            return
        }

        moodMixJob?.cancel()
        _activeMoodMix.value = cleanTitle
        moodMixJob = viewModelScope.launch {
            _statusMessage.value = "Tuning $cleanTitle mix…"
            try {
                val isOffline = settings.value.offlineMode
                val roomSnapshot = libraryRepo.getAllTracksSnapshot()
                val roomById = roomSnapshot.associateBy { it.id }

                val mixTracks: List<Track> = if (isOffline) {
                    val tokens = (cleanTitle + " " + cleanQuery)
                        .lowercase()
                        .split(Regex("[^a-z0-9]+"))
                        .filter { it.length >= 3 }
                    val offlineTracks = libraryRepo.getOfflinePlayableSnapshot()
                    val matched = offlineTracks.filter { t ->
                        tokens.any { tok ->
                            t.mood.contains(tok, ignoreCase = true) ||
                                t.genre.contains(tok, ignoreCase = true) ||
                                t.title.contains(tok, ignoreCase = true) ||
                                t.artist.contains(tok, ignoreCase = true)
                        }
                    }
                    matched.ifEmpty { offlineTracks }
                } else {
                    val (ytHits, audiusHits) = coroutineScope {
                        val ytDeferred = async {
                            try {
                                youTubeClient.searchMusic(cleanQuery, limit = 15)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("PulseVM", "Mood mix YT search error: ${e.message}")
                                emptyList()
                            }
                        }
                        val audiusDeferred = async {
                            try {
                                audiusClient.search(cleanTitle, limit = 6)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        ytDeferred.await() to audiusDeferred.await()
                    }
                    val tokens = cleanTitle.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
                    val localMatched = (allLibraryTracks.value + roomSnapshot)
                        .distinctBy { it.id }
                        .filter { t ->
                            tokens.any { tok ->
                                t.title.contains(tok, true) ||
                                    t.artist.contains(tok, true) ||
                                    t.genre.contains(tok, true) ||
                                    t.mood.contains(tok, true)
                            }
                        }
                    (ytHits + audiusHits + localMatched)
                        .distinctBy { it.id }
                        .map { mergeSavedFlags(it, roomById[it.id]) }
                }

                if (mixTracks.isEmpty()) {
                    _activeMoodMix.value = null
                    _statusMessage.value = if (isOffline) {
                        "No downloaded tracks match \"$cleanTitle\" in Offline Mode"
                    } else {
                        "Couldn't find tracks for \"$cleanTitle\""
                    }
                    return@launch
                }

                val firstTrack = mixTracks.first()
                if (!isOffline) {
                    val topIds = mixTracks.mapNotNull { it.youtubeVideoId }.take(3)
                    if (topIds.isNotEmpty()) {
                        launch(Dispatchers.IO) {
                            runCatching { youTubeClient.prewarmAudioStreams(topIds, maxConcurrent = 3) }
                        }
                    }
                }
                startPlaybackInternal(firstTrack, mixTracks)
                _statusMessage.value = "Playing $cleanTitle · ${firstTrack.title}"

                if (!toggleIfSame) {
                    refreshCatalogAndRecommendations(moodPrompt = cleanTitle, showLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _activeMoodMix.value = null
                _statusMessage.value = e.localizedMessage ?: "Couldn't start $cleanTitle mix"
            }
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
        val nextLiked = !track.isLiked
        // Optimistically update every UI surface (Now Playing, Search, For You, Library) immediately
        syncTrackStateAcrossUi(track.copy(isLiked = nextLiked))

        viewModelScope.launch {
            val updated = libraryRepo.setLiked(track, nextLiked)
            syncTrackStateAcrossUi(updated)
            if (nextLiked) {
                libraryRepo.insertSignal(
                    ListeningSignalEntity(
                        trackId = updated.id,
                        genre = updated.genre,
                        mood = updated.mood,
                        bpm = updated.bpm,
                        completionRatio = 1.0f,
                        earlySkip = false,
                        replayCount = 1,
                        seekBackCount = 0,
                        dwellMs = 15_000L,
                        liked = true,
                        savedOffline = updated.isDownloaded,
                        sharedInDm = false,
                        timeBucket = recommendationEngine.currentTimeBucket()
                    )
                )
            }
            refreshYouTubeLikedRecommendationsInBackground()
        }
    }

    fun downloadTrackOffline(track: Track) {
        if (track.isDownloaded || downloadProgress.value.containsKey(track.id)) return
        viewModelScope.launch {
            _statusMessage.value = "Downloading ${track.title}…"
            val res = downloadManager.downloadTrack(track)
            res.onSuccess { saved ->
                syncTrackStateAcrossUi(saved)
                _statusMessage.value = "Saved \"${saved.title}\" to Downloaded"
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Download failed"
            }
        }
    }

    fun removeOfflineTrack(track: Track) {
        viewModelScope.launch {
            downloadManager.removeOfflineDownload(track)
            val updated = libraryRepo.getTrackById(track.id) ?: track.copy(
                isDownloaded = false,
                downloadedFilePath = "",
                cachedArtworkPath = ""
            )
            syncTrackStateAcrossUi(updated)
            _statusMessage.value = "Removed offline file for ${track.title}"
        }
    }

    private fun mergeSavedFlags(candidate: Track, saved: Track?): Track {
        if (saved == null) return candidate
        return candidate.copy(
            isDownloaded = saved.isDownloaded || candidate.isDownloaded,
            downloadedFilePath = saved.downloadedFilePath.ifBlank { candidate.downloadedFilePath },
            cachedArtworkPath = saved.cachedArtworkPath.ifBlank { candidate.cachedArtworkPath },
            isLiked = saved.isLiked || candidate.isLiked,
            syncedLrcText = saved.syncedLrcText.ifBlank { candidate.syncedLrcText },
            plainLyrics = saved.plainLyrics.ifBlank { candidate.plainLyrics },
            playCount = maxOf(saved.playCount, candidate.playCount),
            lastPlayedAt = maxOf(saved.lastPlayedAt, candidate.lastPlayedAt)
        )
    }

    private fun syncTrackStateAcrossUi(updated: Track) {
        _remoteCatalog.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
        _searchResults.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
        _discoveryState.update { state ->
            if (state is UiState.Success) {
                val bundle = state.data
                state.copy(
                    data = bundle.copy(
                        heroPicks = bundle.heroPicks.map { pick ->
                            if (pick.track.id == updated.id) pick.copy(track = updated) else pick
                        },
                        replayPicks = bundle.replayPicks.map { pick ->
                            if (pick.track.id == updated.id) pick.copy(track = updated) else pick
                        },
                        explorePicks = bundle.explorePicks.map { pick ->
                            if (pick.track.id == updated.id) pick.copy(track = updated) else pick
                        },
                        youtubeLikedRecs = bundle.youtubeLikedRecs.map { rec ->
                            if (rec.id == updated.id) updated else rec
                        }
                    )
                )
            } else {
                state
            }
        }
        playbackController.updateTrackMetadata(updated)
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

    fun scanDeviceAudioTracks(silentIfNoPermission: Boolean = false) {
        if (_isScanningDeviceMusic.value) return
        if (!SafMusicImporter.hasDeviceAudioPermission(appContext)) {
            if (!silentIfNoPermission) {
                _statusMessage.value = "Allow audio permission to auto-load songs from your device"
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningDeviceMusic.value = true
            try {
                val scanned = safImporter.scanDeviceAudio(
                    minDurationMs = SafMusicImporter.DEFAULT_MIN_DURATION_MS
                )
                libraryRepo.syncScannedDeviceTracks(scanned)
                if (!silentIfNoPermission) {
                    _statusMessage.value = if (scanned.isNotEmpty()) {
                        "Loaded ${scanned.size} device song(s) longer than 2 min"
                    } else {
                        "No songs longer than 2 minutes found on device"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PulseVM", "Device audio scan failed: ${e.message}")
                if (!silentIfNoPermission) {
                    _statusMessage.value = "Could not scan device audio files"
                }
            } finally {
                _isScanningDeviceMusic.value = false
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
