package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.firebase.SocialRepository
import com.example.data.firebase.authStateFlow
import com.example.data.firebase.configureAppCheck
import com.example.data.firebase.onGoogleSignInClicked
import com.example.data.firebase.signOutUser
import com.example.data.local.LocalLibraryRepository
import com.example.data.local.PreferencesRepository
import com.example.data.local.PulseDatabase
import com.example.data.model.SonicDnaProfile
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.example.data.model.UserProfile
import com.example.data.remote.LrclibClient
import com.example.data.remote.OfflineDownloadManager
import com.example.data.remote.YouTubeMusicClient
import com.example.domain.RecommendationEngine
import com.example.domain.SafMusicImporter
import com.example.media.PlaybackController
import com.example.media.PlaybackUiState
import com.example.media.PulseMediaService
import com.example.ui.components.GoogleSignInRequiredDialog
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.PulseWaveBrandLogo
import com.example.ui.components.ShareMusicSheet
import com.example.ui.components.YouTubeSignInDialog
import com.example.ui.components.defaultPulseRoomName
import com.example.ui.components.dynamicAmbientGlow
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ForYouScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.PulseRoomScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SocialGuestGateScreen
import com.example.ui.screens.SocialHubScreen
import com.example.ui.screens.VisualizerSettingsScreen
import com.example.ui.theme.PulseWaveTheme
import com.example.ui.viewmodel.PulseViewModel
import com.example.ui.viewmodel.SocialViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data object HomeTabsRoute
@Serializable data class PulseRoomRoute(val roomId: String)
@Serializable data object VisualizerStudioRoute

private data class MainTabSpec(
    val label: String,
    val icon: ImageVector,
    val navTestTag: String,
    val railTestTag: String
)

private data class SettingsPlaybackSnapshot(
    val sleepTimerRemainingMs: Long?,
    val sleepTimerTotalMs: Long?,
    val eqEnabled: Boolean,
    val activeSoundscape: SoundscapePreset,
    val bandLevels: List<Int>,
    val bassBoost: Int,
    val reverbPreset: Int
)

class MainActivity : ComponentActivity() {
    private val openNowPlayingSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        handlePlaybackIntent(intent)

        setContent {
            val currentUser by Firebase.auth.authStateFlow()
                .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

            var skipAuthForGuest by rememberSaveable { mutableStateOf(false) }

            val notifLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ -> }

            LaunchedEffect(currentUser?.uid, skipAuthForGuest) {
                if (currentUser != null) {
                    skipAuthForGuest = false
                }
                if ((currentUser != null || skipAuthForGuest) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            if (currentUser == null && !skipAuthForGuest) {
                PulseWaveTheme {
                    AuthScreen(
                        onAuthSuccess = { /* authStateFlow advances automatically */ },
                        onSkip = { skipAuthForGuest = true }
                    )
                }
            } else {
                val isSignedIn = currentUser != null
                val uid = currentUser?.uid ?: "guest"
                val openSignal = openNowPlayingSignal.intValue
                key(uid) {
                    AuthenticatedPulseApp(
                        uid = uid,
                        isSignedIn = isSignedIn,
                        openNowPlayingSignal = openSignal,
                        onConsumeOpenNowPlaying = { openNowPlayingSignal.intValue = 0 },
                        onSignOutComplete = { skipAuthForGuest = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlaybackIntent(intent)
    }

    private fun handlePlaybackIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PulseMediaService.EXTRA_OPEN_NOW_PLAYING, false) == true) {
            openNowPlayingSignal.intValue += 1
        }
    }
}

@Composable
private fun AuthenticatedPulseApp(
    uid: String,
    isSignedIn: Boolean,
    openNowPlayingSignal: Int,
    onConsumeOpenNowPlaying: () -> Unit,
    onSignOutComplete: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val pulseViewModel: PulseViewModel = viewModel(
        key = "pulse_$uid",
        factory = viewModelFactory {
            initializer {
                val db = PulseDatabase.getInstance(appContext)
                val libraryRepo = LocalLibraryRepository(db)
                val prefs = PreferencesRepository(appContext)
                val lrclib = LrclibClient()
                val youtube = YouTubeMusicClient.shared
                val downloader = OfflineDownloadManager(appContext, libraryRepo, lrclib, youtube)
                val saf = SafMusicImporter(appContext)
                val engine = RecommendationEngine()
                val controller = PlaybackController(appContext, db.signalDao())
                PulseViewModel(
                    appContext = appContext,
                    libraryRepo = libraryRepo,
                    prefsRepo = prefs,
                    lrclibClient = lrclib,
                    downloadManager = downloader,
                    safImporter = saf,
                    recommendationEngine = engine,
                    playbackController = controller,
                    youTubeClient = youtube
                )
            }
        }
    )

    val socialViewModel: SocialViewModel? = if (isSignedIn) {
        viewModel(
            key = "social_$uid",
            factory = viewModelFactory {
                initializer {
                    val firestore = FirebaseFirestore.getInstance(
                        appContext.getString(R.string.firestore_database_id)
                    )
                    SocialViewModel(SocialRepository(firestore), uid)
                }
            }
        )
    } else {
        null
    }

    val settings by pulseViewModel.settings.collectAsStateWithLifecycle()
    val activeTrack by remember(pulseViewModel) {
        pulseViewModel.playbackState.map { it.currentTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember(pulseViewModel) {
        pulseViewModel.playbackState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val activeSoundscape by remember(pulseViewModel) {
        pulseViewModel.playbackState.map { it.activeSoundscape }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = pulseViewModel.playbackState.value.activeSoundscape)
    val settingsPlayback by remember(pulseViewModel) {
        pulseViewModel.playbackState.map {
            SettingsPlaybackSnapshot(
                sleepTimerRemainingMs = it.sleepTimerRemainingMs,
                sleepTimerTotalMs = it.sleepTimerTotalMs,
                eqEnabled = it.eqEnabled,
                activeSoundscape = it.activeSoundscape,
                bandLevels = it.bandLevels,
                bassBoost = it.bassBoost,
                reverbPreset = it.reverbPreset
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = with(pulseViewModel.playbackState.value) {
            SettingsPlaybackSnapshot(
                sleepTimerRemainingMs = sleepTimerRemainingMs,
                sleepTimerTotalMs = sleepTimerTotalMs,
                eqEnabled = eqEnabled,
                activeSoundscape = activeSoundscape,
                bandLevels = bandLevels,
                bassBoost = bassBoost,
                reverbPreset = reverbPreset
            )
        }
    )
    val discoveryState by pulseViewModel.discoveryState.collectAsStateWithLifecycle()
    val liveSonicDna by pulseViewModel.sonicDnaProfile.collectAsStateWithLifecycle()
    val libraryTracks by pulseViewModel.allLibraryTracks.collectAsStateWithLifecycle()
    val searchResults by pulseViewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by pulseViewModel.isSearching.collectAsStateWithLifecycle()
    val searchError by pulseViewModel.searchError.collectAsStateWithLifecycle()
    val downloadProgress by pulseViewModel.downloadProgress.collectAsStateWithLifecycle()
    val parsedLyrics by pulseViewModel.parsedLyrics.collectAsStateWithLifecycle()
    val isLoadingLyrics by pulseViewModel.isLoadingLyrics.collectAsStateWithLifecycle()
    val isSyncingLocalLyrics by pulseViewModel.isSyncingLocalLyrics.collectAsStateWithLifecycle()
    val isScanningDeviceMusic by pulseViewModel.isScanningDeviceMusic.collectAsStateWithLifecycle()
    val aiTrackStory by pulseViewModel.aiTrackStory.collectAsStateWithLifecycle()
    val albumAccent by pulseViewModel.albumAccent.collectAsStateWithLifecycle()
    val offlineCacheSummary by pulseViewModel.offlineCacheSummary.collectAsStateWithLifecycle()
    val statusMessage by pulseViewModel.statusMessage.collectAsStateWithLifecycle()
    val activeMoodMix by pulseViewModel.activeMoodMix.collectAsStateWithLifecycle()

    val guestProfile = remember {
        UserProfile(
            id = "guest",
            userId = "guest",
            displayName = "Guest Mode",
            handle = "guest"
        )
    }
    val currentUserProfile = socialViewModel?.currentUserProfile?.collectAsStateWithLifecycle()?.value ?: guestProfile
    val discoverableUsersState = socialViewModel?.discoverableUsers?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val friendRequestsState = socialViewModel?.friendRequests?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val feedPostsState = socialViewModel?.feedPosts?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val activePostComments = socialViewModel?.activePostComments?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val conversationsState = socialViewModel?.conversations?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val activeMessages = socialViewModel?.activeMessages?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val playlistsState = socialViewModel?.collaborativePlaylists?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val activeRoomsState = socialViewModel?.activePulseRooms?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val syncedLibraryState = socialViewModel?.syncedLibrary?.collectAsStateWithLifecycle()?.value ?: UiState.Success(emptyList())
    val activeRoom = socialViewModel?.activeRoom?.collectAsStateWithLifecycle()?.value
    val activeRoomQueue = socialViewModel?.activeRoomQueue?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val activeRoomReactions = socialViewModel?.activeRoomReactions?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val activeLyricNotes = socialViewModel?.activeLyricNotes?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val socialNotice = socialViewModel?.socialNotice?.collectAsStateWithLifecycle()?.value

    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var showNowPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(openNowPlayingSignal, activeTrack?.id) {
        if (openNowPlayingSignal > 0 && activeTrack != null) {
            showNowPlaying = true
            onConsumeOpenNowPlaying()
        }
    }
    var shareTargetTrack by remember { mutableStateOf<Track?>(null) }
    var isSigningIn by remember { mutableStateOf(false) }
    var pendingSignInFeature by remember { mutableStateOf<String?>(null) }
    var showYouTubeSignInDialog by remember { mutableStateOf(false) }

    fun triggerGoogleSignIn() {
        if (isSigningIn) return
        isSigningIn = true
        onGoogleSignInClicked(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isSigningIn = false
                pendingSignInFeature = null
            },
            onAuthError = { msg ->
                isSigningIn = false
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
            scope = scope,
            onAuthCancelled = {
                isSigningIn = false
            }
        )
    }

    val sonicDna = remember(liveSonicDna, currentUserProfile) {
        if (liveSonicDna.totalPlays > 0) {
            liveSonicDna
        } else {
            SonicDnaProfile(
                energy = currentUserProfile.sonicEnergy.toInt(),
                valence = currentUserProfile.sonicValence.toInt(),
                tempo = currentUserProfile.sonicTempo.toInt(),
                acoustic = currentUserProfile.sonicAcoustic.toInt(),
                electronic = currentUserProfile.sonicElectronic.toInt(),
                chill = currentUserProfile.sonicChill.toInt(),
                topGenres = currentUserProfile.topGenres.ifEmpty { liveSonicDna.topGenres },
                topMoods = liveSonicDna.topMoods,
                totalPlays = liveSonicDna.totalPlays,
                avgCompletionPct = liveSonicDna.avgCompletionPct
            )
        }
    }

    // Keep recommendation engine & Firestore lyric/Sonic DNA context synchronized when signed in
    LaunchedEffect(feedPostsState, isSignedIn) {
        if (isSignedIn && feedPostsState is UiState.Success) {
            pulseViewModel.updateFriendPostsContext(feedPostsState.data)
        }
    }

    LaunchedEffect(activeTrack?.id, liveSonicDna.totalPlays, isSignedIn) {
        val t = activeTrack ?: return@LaunchedEffect
        if (isSignedIn && socialViewModel != null) {
            socialViewModel.observeLyricNotesForTrack(t.trackKey)
            socialViewModel.syncSonicDna(sonicDna, t)
        }
    }

    LaunchedEffect(showNowPlaying) {
        pulseViewModel.setNowPlayingVisible(showNowPlaying)
        if (showNowPlaying) {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            pulseViewModel.clearStatusMessage()
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(socialNotice) {
        socialNotice?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            socialViewModel.clearNotice()
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
        }
    }

    val allUsers = (discoverableUsersState as? UiState.Success)?.data.orEmpty()
    val acceptedRequests = (friendRequestsState as? UiState.Success)?.data.orEmpty().filter { it.status == "ACCEPTED" }
    val friendUids = acceptedRequests.flatMap { it.participantUids }.filter { it != uid }.toSet()
    val connectedFriends = allUsers.filter { it.userId in friendUids }
    val playlists = (playlistsState as? UiState.Success)?.data.orEmpty()
    val resolvedUserDisplayName = remember(currentUserProfile.displayName) {
        currentUserProfile.displayName.takeIf { it.isNotBlank() && it != "Pulse Listener" }
            ?: Firebase.auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: currentUserProfile.displayName
    }
    val defaultRoomName = remember(resolvedUserDisplayName) {
        defaultPulseRoomName(resolvedUserDisplayName)
    }
    val joinedPulseRoom = remember(activeRoom) {
        activeRoom?.takeIf { it.active }
    }

    PulseWaveTheme(
        themeMode = settings.themeMode,
        albumAccentOverride = albumAccent
    ) {
        val glowColor = albumAccent ?: MaterialTheme.colorScheme.primary
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (settings.dynamicAlbumGlow) {
                        Modifier.dynamicAmbientGlow(glowColor, MaterialTheme.colorScheme.secondary, intensity = 0.18f)
                    } else {
                        Modifier
                    }
                )
        ) {
            val isExpandedScreen = maxWidth >= 840.dp

            val tabItems = remember {
                listOf(
                    MainTabSpec("For You", Icons.Rounded.AutoAwesome, "nav_for_you", "rail_for_you"),
                    MainTabSpec("Library", Icons.Rounded.LibraryMusic, "nav_library", "rail_library"),
                    MainTabSpec("Social", Icons.Rounded.Groups, "nav_social", "rail_social"),
                    MainTabSpec("Settings", Icons.Rounded.Settings, "nav_settings", "rail_settings")
                )
            }
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabItems.size })
            val isOnRoomRoute = currentDestination?.hasRoute(PulseRoomRoute::class) == true
            val isOnVisualizerRoute = currentDestination?.hasRoute(VisualizerStudioRoute::class) == true
            val isOnSubRoute = isOnRoomRoute || isOnVisualizerRoute

            fun selectTab(index: Int) {
                if (isOnSubRoute) {
                    navController.popBackStack<HomeTabsRoute>(inclusive = false)
                }
                if (pagerState.currentPage == index) return
                scope.launch {
                    val current = pagerState.currentPage
                    if (abs(index - current) > 1) {
                        val neighbor = if (index > current) index - 1 else index + 1
                        pagerState.scrollToPage(neighbor)
                    }
                    pagerState.animateScrollToPage(
                        page = index,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                    )
                }
            }

            BackHandler(enabled = !isOnSubRoute && !showNowPlaying && pagerState.currentPage != 0) {
                selectTab(0)
            }

            val hasFloatingMiniPlayer = activeTrack != null && !showNowPlaying && !isOnSubRoute

            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .padding(
                                start = 14.dp,
                                end = 14.dp,
                                bottom = if (hasFloatingMiniPlayer) 84.dp else 8.dp
                            )
                            .widthIn(max = 560.dp)
                    ) { data ->
                        val msg = data.visuals.message
                        val isConnectivity = msg.contains("offline", ignoreCase = true) ||
                            msg.contains("Network error", ignoreCase = true) ||
                            msg.contains("timed out", ignoreCase = true)
                        val isError = isConnectivity ||
                            msg.contains("failed", ignoreCase = true) ||
                            msg.contains("couldn't", ignoreCase = true) ||
                            msg.contains("unavailable", ignoreCase = true) ||
                            msg.contains("rejected", ignoreCase = true) ||
                            msg.contains("denied", ignoreCase = true) ||
                            msg.contains("expired", ignoreCase = true) ||
                            msg.contains("could not", ignoreCase = true)
                        val containerColor = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.inverseSurface
                        }
                        val contentColor = if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.inverseOnSurface
                        }
                        val borderColor = if (isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = containerColor,
                            contentColor = contentColor,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp,
                            border = BorderStroke(1.dp, borderColor)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        isConnectivity -> Icons.Rounded.WifiOff
                                        isError -> Icons.Rounded.ErrorOutline
                                        else -> Icons.Rounded.Info
                                    },
                                    contentDescription = null,
                                    tint = if (isError) MaterialTheme.colorScheme.error else contentColor.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentColor,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { data.dismiss() },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss notification",
                                        tint = contentColor.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    if (!isExpandedScreen && !isOnSubRoute) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        ) {
                            tabItems.forEachIndexed { index, tab ->
                                val selected = !isOnSubRoute && pagerState.targetPage == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { selectTab(index) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag(tab.navTestTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpandedScreen && !isOnSubRoute) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            header = {
                                PulseWaveBrandLogo(
                                    size = 42.dp,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                                )
                            }
                        ) {
                            tabItems.forEachIndexed { index, tab ->
                                val selected = !isOnSubRoute && pagerState.targetPage == index
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { selectTab(index) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag(tab.railTestTag)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .statusBarsPadding(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = HomeTabsRoute,
                            modifier = Modifier.widthIn(max = 960.dp).fillMaxSize()
                        ) {
                            composable<HomeTabsRoute> {
                                HorizontalPager(
                                    state = pagerState,
                                    beyondViewportPageCount = 3,
                                    userScrollEnabled = false,
                                    key = { it },
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                                    .coerceIn(-1f, 1f)
                                                alpha = 1f - (abs(pageOffset) * 0.22f)
                                            }
                                    ) {
                                        when (page) {
                                            0 -> ForYouScreen(
                                                discoveryState = discoveryState,
                                                activeTrackId = activeTrack?.id,
                                                isPlaying = isPlaying,
                                                activeMoodMix = activeMoodMix,
                                                downloadProgress = downloadProgress,
                                                offlineMode = settings.offlineMode,
                                                onRefreshWithMood = { mood -> pulseViewModel.refreshCatalogAndRecommendations(mood) },
                                                onPlayMoodMix = { mix ->
                                                    pulseViewModel.playMoodMix(
                                                        moodTitle = mix.name,
                                                        searchQuery = mix.searchQuery,
                                                        toggleIfSame = true
                                                    )
                                                },
                                                onPlayTrack = { track, queue ->
                                                    pulseViewModel.playTrack(track, queue)
                                                    socialViewModel?.syncTrackBookmark(track, track.isLiked)
                                                },
                                                onToggleLike = { track ->
                                                    pulseViewModel.toggleLikeTrack(track)
                                                    socialViewModel?.syncTrackBookmark(track, !track.isLiked)
                                                },
                                                onDownloadClick = { track ->
                                                    if (track.isDownloaded) pulseViewModel.removeOfflineTrack(track)
                                                    else pulseViewModel.downloadTrackOffline(track)
                                                },
                                                onShareClick = { track ->
                                                    if (isSignedIn) shareTargetTrack = track
                                                    else pendingSignInFeature = "sharing tracks"
                                                },
                                                onRecordDwell = { track, dwell -> pulseViewModel.recordFeedCardDwell(track, dwell) },
                                                onDismissDiscoveryTrack = { track -> pulseViewModel.dismissDiscoveryTrack(track) }
                                            )

                                            1 -> LibraryScreen(
                                                tracks = libraryTracks,
                                                searchResults = searchResults,
                                                isSearching = isSearching,
                                                searchError = searchError,
                                                activeTrack = activeTrack,
                                                downloadProgress = downloadProgress,
                                                offlineMode = settings.offlineMode,
                                                playlistsState = playlistsState,
                                                syncedLibraryState = syncedLibraryState,
                                                isScanningDeviceMusic = isScanningDeviceMusic,
                                                isSignedIn = isSignedIn,
                                                isSigningIn = isSigningIn,
                                                onSignInWithGoogle = { triggerGoogleSignIn() },
                                                onScanDeviceAudio = { silent -> pulseViewModel.scanDeviceAudioTracks(silent) },
                                                onSearchQuery = { pulseViewModel.searchTracks(it) },
                                                onPlayTrack = { track, queue ->
                                                    pulseViewModel.playTrack(track, queue)
                                                    socialViewModel?.syncTrackBookmark(track, track.isLiked)
                                                },
                                                onToggleLike = { track ->
                                                    pulseViewModel.toggleLikeTrack(track)
                                                    socialViewModel?.syncTrackBookmark(track, !track.isLiked)
                                                },
                                                onDownloadClick = { track ->
                                                    if (track.isDownloaded) pulseViewModel.removeOfflineTrack(track)
                                                    else pulseViewModel.downloadTrackOffline(track)
                                                },
                                                onShareClick = { track ->
                                                    if (isSignedIn) shareTargetTrack = track
                                                    else pendingSignInFeature = "sharing tracks"
                                                },
                                                onCreatePlaylist = { title, desc, seeds ->
                                                    socialViewModel?.createPlaylist(title, desc, listOf(uid), seeds)
                                                },
                                                onAddActiveTrackToPlaylist = { pl, t -> socialViewModel?.addTrackToPlaylist(pl, t) },
                                                onUpvotePlaylistTrack = { pl, tid -> socialViewModel?.upvotePlaylistTrack(pl, tid) },
                                                onRemovePlaylistTrack = { pl, tid -> socialViewModel?.removeTrackFromPlaylist(pl, tid) }
                                            )

                                            2 -> if (!isSignedIn || socialViewModel == null) {
                                                SocialGuestGateScreen(
                                                    isSigningIn = isSigningIn,
                                                    onSignInWithGoogle = { triggerGoogleSignIn() }
                                                )
                                            } else {
                                                SocialHubScreen(
                                                    currentUserId = uid,
                                                    currentUserProfile = currentUserProfile,
                                                    mySonicDna = sonicDna,
                                                    activeTrack = activeTrack,
                                                    activeSoundscapeName = activeSoundscape.name,
                                                    libraryTracks = libraryTracks,
                                                    discoverableUsersState = discoverableUsersState,
                                                    friendRequestsState = friendRequestsState,
                                                    feedPostsState = feedPostsState,
                                                    activePostComments = activePostComments,
                                                    conversationsState = conversationsState,
                                                    activeMessages = activeMessages,
                                                    activeRoomsState = activeRoomsState,
                                                    computeTasteCompatibility = { socialViewModel.computeTasteCompatibility(it) },
                                                    defaultRoomName = defaultRoomName,
                                                    onPlayTrack = { track -> pulseViewModel.playTrack(track, listOf(track)) },
                                                    onPublishPost = { track, cap, quote, preset ->
                                                        socialViewModel.publishPost(track, cap, "TRACK", quote, preset)
                                                    },
                                                    onTogglePostLike = { socialViewModel.togglePostLike(it) },
                                                    onOpenPostComments = { socialViewModel.openPostComments(it) },
                                                    onAddPostComment = { post, text -> socialViewModel.addPostComment(post, text) },
                                                    onSendFriendRequest = { socialViewModel.sendFriendRequest(it) },
                                                    onAcceptFriendRequest = { socialViewModel.acceptFriendRequest(it) },
                                                    onCreateTasteBlend = { friend ->
                                                        socialViewModel.createTasteBlendPlaylist(friend, libraryTracks)
                                                    },
                                                    onOpenConversation = { socialViewModel.openConversation(it) },
                                                    onSendDm = { recipient, text, track, type, presetName ->
                                                        socialViewModel.sendDm(
                                                            recipient = recipient,
                                                            text = text,
                                                            attachedTrack = track,
                                                            attachmentType = type,
                                                            presetName = presetName
                                                        )
                                                    },
                                                    onCreatePulseRoom = { roomName, moodTag, seedTrack ->
                                                        pulseViewModel.playTrack(seedTrack)
                                                        socialViewModel.createAndJoinPulseRoom(
                                                            roomName = roomName,
                                                            moodTag = moodTag,
                                                            track = seedTrack,
                                                            soundscapePreset = activeSoundscape.name
                                                        ) { createdRoomId ->
                                                            navController.navigate(PulseRoomRoute(createdRoomId))
                                                        }
                                                    },
                                                    onJoinPulseRoom = { room ->
                                                        socialViewModel.joinAndObservePulseRoom(room) {
                                                            navController.navigate(PulseRoomRoute(room.id))
                                                        }
                                                    }
                                                )
                                            }

                                            else -> {
                                                val syncedCount = (syncedLibraryState as? UiState.Success)?.data?.size ?: 0
                                                SettingsScreen(
                                                    currentUserProfile = currentUserProfile,
                                                    settings = settings,
                                                    tracks = libraryTracks,
                                                    syncedCount = syncedCount,
                                                    sleepTimerRemainingMs = settingsPlayback.sleepTimerRemainingMs,
                                                    sleepTimerTotalMs = settingsPlayback.sleepTimerTotalMs,
                                                    eqEnabled = settingsPlayback.eqEnabled,
                                                    activeEqPreset = settingsPlayback.activeSoundscape,
                                                    eqBandLevels = settingsPlayback.bandLevels,
                                                    eqBassBoost = settingsPlayback.bassBoost,
                                                    eqReverbPreset = settingsPlayback.reverbPreset,
                                                    offlineCacheSummary = offlineCacheSummary,
                                                    isSyncingLocalLyrics = isSyncingLocalLyrics,
                                                    isSignedIn = isSignedIn,
                                                    isSigningIn = isSigningIn,
                                                    onSignInWithGoogle = { triggerGoogleSignIn() },
                                                    onRequireSignIn = { feature -> pendingSignInFeature = feature },
                                                    onToggleOfflineMode = { pulseViewModel.setOfflineMode(it) },
                                                    onToggleHighResStreaming = { pulseViewModel.setHighResStreaming(it) },
                                                    onCrossfadeSecondsChange = { pulseViewModel.setCrossfadeSeconds(it) },
                                                    onToggleSmartFadeOut = { pulseViewModel.setSmartFadeOut(it) },
                                                    onToggleEqEnabled = { pulseViewModel.setEqualizerEnabled(it) },
                                                    onSelectEqPreset = { pulseViewModel.applySoundscapePreset(it) },
                                                    onUpdateEqBand = { idx, mb -> pulseViewModel.updateEqualizerBand(idx, mb) },
                                                    onUpdateEqBassBoost = { pulseViewModel.updateBassBoost(it) },
                                                    onUpdateEqReverb = { pulseViewModel.updateReverbPreset(it) },
                                                    onResetEqualizer = { pulseViewModel.resetEqualizer() },
                                                    onStartSleepTimer = { pulseViewModel.startSleepTimer(it) },
                                                    onExtendSleepTimer = { pulseViewModel.extendSleepTimer(it) },
                                                    onCancelSleepTimer = { pulseViewModel.cancelSleepTimer() },
                                                    onSelectThemeMode = { pulseViewModel.setThemeMode(it) },
                                                    onToggleDynamicGlow = { pulseViewModel.setDynamicAlbumGlow(it) },
                                                    onOpenVisualizerSettings = { navController.navigate(VisualizerStudioRoute) },
                                                    onImportSafAudio = { uris, isCloud -> pulseViewModel.importSafAudio(uris, isCloud) },
                                                    onSyncLocalLyrics = { pulseViewModel.syncLocalTracksMetadataAndLyrics() },
                                                    onClearMetadataCache = { pulseViewModel.clearMetadataCache() },
                                                    onConnectYouTube = { showYouTubeSignInDialog = true },
                                                    onDisconnectYouTube = { pulseViewModel.disconnectYouTubeSession() },
                                                    onSignOut = {
                                                        signOutUser(credentialManager, scope, onSignOutComplete = onSignOutComplete)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            composable<PulseRoomRoute> { entry ->
                                val route = entry.toRoute<PulseRoomRoute>()
                                val roomPlaybackState by pulseViewModel.playbackState.collectAsStateWithLifecycle()
                                LaunchedEffect(route.roomId, isSignedIn) {
                                    if (isSignedIn && socialViewModel != null && activeRoom?.id != route.roomId) {
                                        socialViewModel.observePulseRoomSession(route.roomId)
                                    }
                                }
                                PulseRoomScreen(
                                    roomId = route.roomId,
                                    currentUserId = uid,
                                    room = activeRoom,
                                    queue = activeRoomQueue,
                                    reactions = activeRoomReactions,
                                    playbackState = roomPlaybackState,
                                    libraryTracks = libraryTracks,
                                    onBack = { navController.popBackStack() },
                                    onEndOrLeaveRoom = {
                                        socialViewModel?.endOrLeavePulseRoom(activeRoom) {
                                            navController.popBackStack()
                                        } ?: navController.popBackStack()
                                    },
                                    onRenameRoom = { newName ->
                                        socialViewModel?.renamePulseRoom(route.roomId, newName)
                                    },
                                    onSyncListenerPlayback = { track, playing, posMs, preset ->
                                        pulseViewModel.playbackController.syncToRoomPlayback(track, playing, posMs)
                                        preset?.let { pulseViewModel.applySoundscapePreset(it) }
                                    },
                                    onHostBroadcastPlayback = { track, playing, posMs, presetName ->
                                        if (roomPlaybackState.currentTrack?.id != track.id) {
                                            pulseViewModel.playTrack(track)
                                        }
                                        socialViewModel?.syncHostRoomState(route.roomId, track, playing, posMs, presetName)
                                    },
                                    onToggleHostPlayPause = { pulseViewModel.playbackController.togglePlayPause() },
                                    onEnqueueTrack = { track -> socialViewModel?.enqueueRoomTrack(route.roomId, track) },
                                    onRemoveQueueItem = { item -> socialViewModel?.removeRoomQueueItem(route.roomId, item.id) },
                                    onSendReaction = { emoji -> socialViewModel?.sendRoomReaction(route.roomId, emoji) }
                                )
                            }

                            composable<VisualizerStudioRoute> {
                                val studioPlaybackState by pulseViewModel.playbackState.collectAsStateWithLifecycle()
                                VisualizerSettingsScreen(
                                    settings = settings,
                                    playbackState = studioPlaybackState,
                                    albumAccent = albumAccent,
                                    onSelectVisualizerStyle = { pulseViewModel.setVisualizerStyleIndex(it) },
                                    onUpdateVisualizerLayout = { h, vPad, hPad ->
                                        pulseViewModel.setVisualizerLayout(h, vPad, hPad)
                                    },
                                    onUpdateVisualizerTuning = { motion, intensity ->
                                        pulseViewModel.setVisualizerTuning(motion, intensity)
                                    },
                                    onResetVisualizerSettings = { pulseViewModel.resetVisualizerSettings() },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }

                        // Persistent MiniPlayer anchored cleanly above navigation bar (isolated from tab pager state)
                        if (activeTrack != null && !showNowPlaying && !isOnSubRoute) {
                            LiveMiniPlayerOverlay(
                                playbackStateFlow = pulseViewModel.playbackState,
                                onTogglePlay = { pulseViewModel.playbackController.togglePlayPause() },
                                onSkipNext = { pulseViewModel.playbackController.skipNext() },
                                onOpenNowPlaying = { showNowPlaying = true },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .widthIn(max = 600.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            if (showNowPlaying && activeTrack != null) {
                val nowPlayingState by pulseViewModel.playbackState.collectAsStateWithLifecycle()
                NowPlayingScreen(
                    playbackState = nowPlayingState,
                    parsedLyrics = parsedLyrics,
                    isLoadingLyrics = isLoadingLyrics,
                    aiTrackStory = aiTrackStory,
                    lyricNotes = activeLyricNotes,
                    albumAccent = albumAccent,
                    dynamicGlowEnabled = settings.dynamicAlbumGlow,
                    downloadProgress = downloadProgress[nowPlayingState.currentTrack?.id],
                    visualizerStyleIndex = settings.visualizerStyleIndex,
                    visualizerHeightDp = settings.visualizerHeightDp,
                    visualizerVerticalPaddingDp = settings.visualizerVerticalPaddingDp,
                    visualizerHorizontalPaddingDp = settings.visualizerHorizontalPaddingDp,
                    visualizerParticleMotionScale = settings.visualizerParticleMotionScale,
                    visualizerIntensityScale = settings.visualizerIntensityScale,
                    activePulseRoom = joinedPulseRoom,
                    currentUserId = uid,
                    isSignedIn = isSignedIn,
                    knownUsers = allUsers,
                    defaultRoomName = defaultRoomName,
                    onRequestSignIn = { feature -> pendingSignInFeature = feature },
                    onConnectYouTube = { showYouTubeSignInDialog = true },
                    onRetryPlayback = { pulseViewModel.playbackController.retryActiveTrack() },
                    onOpenPulseRoom = { room ->
                        showNowPlaying = false
                        socialViewModel?.observePulseRoomSession(room.id)
                        navController.navigate(PulseRoomRoute(room.id))
                    },
                    onClose = { showNowPlaying = false },
                    onTogglePlayPause = { pulseViewModel.playbackController.togglePlayPause() },
                    onSeekTo = { pulseViewModel.playbackController.seekTo(it) },
                    onSkipNext = { pulseViewModel.playbackController.skipNext() },
                    onSkipPrevious = { pulseViewModel.playbackController.skipPrevious() },
                    onToggleShuffle = { pulseViewModel.playbackController.toggleShuffle() },
                    onCycleRepeat = { pulseViewModel.playbackController.cycleRepeatMode() },
                    onToggleLike = { track ->
                        pulseViewModel.toggleLikeTrack(track)
                        socialViewModel?.syncTrackBookmark(track, !track.isLiked)
                    },
                    onDownloadClick = { track ->
                        if (track.isDownloaded) pulseViewModel.removeOfflineTrack(track)
                        else pulseViewModel.downloadTrackOffline(track)
                    },
                    onShareClick = { track ->
                        if (isSignedIn) shareTargetTrack = track
                        else pendingSignInFeature = "sharing tracks"
                    },
                    onStartPulseRoom = { roomName, moodTag, track ->
                        if (isSignedIn && socialViewModel != null) {
                            showNowPlaying = false
                            socialViewModel.createAndJoinPulseRoom(
                                roomName = roomName,
                                moodTag = moodTag,
                                track = track,
                                soundscapePreset = activeSoundscape.name
                            ) { createdId ->
                                navController.navigate(PulseRoomRoute(createdId))
                            }
                        } else {
                            pendingSignInFeature = "PulseRooms co-listening"
                        }
                    },
                    onRetryLyrics = { track, customTitle, customArtist ->
                        pulseViewModel.refreshLyricsForTrack(track, customTitle, customArtist)
                    },
                    onGenerateAiStory = { pulseViewModel.generateAiVibeStory(it) },
                    onAddLyricNote = { track, posMs, text ->
                        if (isSignedIn && socialViewModel != null) {
                            socialViewModel.addLyricNote(track.trackKey, posMs, text)
                        } else {
                            pendingSignInFeature = "shared lyric notes"
                        }
                    },
                    onPlayQueueTrack = { selected ->
                        pulseViewModel.playTrack(selected, nowPlayingState.queue.ifEmpty { listOf(selected) })
                    },
                    onRemoveQueueTrack = { idx ->
                        pulseViewModel.playbackController.removeFromQueue(idx)
                    },
                    onStartSleepTimer = { mins -> pulseViewModel.startSleepTimer(mins) },
                    onCancelSleepTimer = { pulseViewModel.cancelSleepTimer() },
                    onToggleEqEnabled = { pulseViewModel.setEqualizerEnabled(it) },
                    onSelectEqPreset = { pulseViewModel.applySoundscapePreset(it) },
                    onUpdateEqBand = { idx, mb -> pulseViewModel.updateEqualizerBand(idx, mb) },
                    onUpdateEqBassBoost = { pulseViewModel.updateBassBoost(it) },
                    onUpdateEqReverb = { pulseViewModel.updateReverbPreset(it) },
                    onResetEqualizer = { pulseViewModel.resetEqualizer() },
                    onOpenVisualizerSettings = {
                        showNowPlaying = false
                        navController.navigate(VisualizerStudioRoute)
                    }
                )
            }

            if (isSignedIn && socialViewModel != null) {
                shareTargetTrack?.let { track ->
                    ShareMusicSheet(
                        track = track,
                        activeSoundscapeName = activeSoundscape.name,
                        friends = connectedFriends.ifEmpty { allUsers.filter { it.userId != uid } },
                        playlists = playlists,
                        activeRoomId = activeRoom?.id,
                        onShareToFeed = { caption, lyricQuote ->
                            socialViewModel.publishPost(
                                track = track,
                                caption = caption,
                                postType = "TRACK",
                                lyricQuote = lyricQuote,
                                soundscapeLabel = activeSoundscape.name
                            )
                        },
                        onSendToFriendDm = { friend, text ->
                            socialViewModel.sendDm(
                                recipient = friend,
                                text = text,
                                attachedTrack = track,
                                attachmentType = "TRACK"
                            )
                        },
                        onAddToPlaylist = { pl -> socialViewModel.addTrackToPlaylist(pl, track) },
                        onQueueInPulseRoom = {
                            activeRoom?.id?.let { rid -> socialViewModel.enqueueRoomTrack(rid, track) }
                        },
                        onDismiss = { shareTargetTrack = null }
                    )
                }
            }

            pendingSignInFeature?.let { featureTitle ->
                GoogleSignInRequiredDialog(
                    featureTitle = featureTitle,
                    isSigningIn = isSigningIn,
                    onDismiss = { pendingSignInFeature = null },
                    onSignInWithGoogle = { triggerGoogleSignIn() }
                )
            }

            if (showYouTubeSignInDialog) {
                YouTubeSignInDialog(
                    onDismiss = { showYouTubeSignInDialog = false },
                    onSessionCaptured = { cookie, visitorData, label ->
                        showYouTubeSignInDialog = false
                        pulseViewModel.saveYouTubeSession(cookie, visitorData, label)
                    }
                )
            }
        }
    }
}

@Composable
private fun LiveMiniPlayerOverlay(
    playbackStateFlow: StateFlow<PlaybackUiState>,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by playbackStateFlow.collectAsStateWithLifecycle()
    val track = state.currentTrack ?: return
    MiniPlayerBar(
        track = track,
        isPlaying = state.isPlaying,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        onTogglePlay = onTogglePlay,
        onSkipNext = onSkipNext,
        onOpenNowPlaying = onOpenNowPlaying,
        modifier = modifier
    )
}
