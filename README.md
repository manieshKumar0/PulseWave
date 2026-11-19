<div align="center">
  <img src="app/src/main/res/drawable/ic_pulsewave_logo.png" alt="PulseWave Logo" width="140" />

  <h1>PulseWave</h1>
  <p><strong>AI-Assisted Social Music Player with Live Soundscape Morphing, Synced Hinglish/Native Lyrics, Offline Room Vault & Collaborative PulseRooms</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android API 24+" />
    <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.2.10" />
    <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose Material 3" />
    <img src="https://img.shields.io/badge/Media3-ExoPlayer%201.6.1-FF6F00?style=flat-square" alt="Media3 ExoPlayer" />
    <img src="https://img.shields.io/badge/Room-2.7.0-00897B?style=flat-square" alt="Room 2.7.0" />
    <img src="https://img.shields.io/badge/Firebase-Auth%20%7C%20Firestore%20%7C%20AI%20Logic-FFCA28?style=flat-square&logo=firebase&logoColor=black" alt="Firebase" />
  </p>
</div>

---

## Overview

**PulseWave** is a modern Kotlin + Jetpack Compose Android music application built around **Material Design 3**, combining live streaming discovery, on-device offline persistence, real-time audio DSP morphing, timed lyrics with automatic Hindi-to-Hinglish transliteration, and social co-listening via Firebase Firestore.

Users can either sign in with **Google (`CredentialManager`)** for full cloud sync and social features, or tap **Skip** on the onboarding horizon screen to explore **Guest Mode** with full access to local streaming, offline downloads, lyrics, soundscapes, and audio imports.

---

## Key Features

### 1. Onboarding, Google Sign-In & Guest Mode
- **Teal Horizon Auth Screen**: Full-bleed edge-to-edge horizon backdrop (`bg_auth_teal_horizon.png`) paired with the glossy teal PulseWave emblem.
- **One-Tap Google Sign-In**: Uses Jetpack `CredentialManager` (`GetGoogleIdOption` for silent session restore + `GetSignInWithGoogleOption` for explicit login) backed by Firebase Auth.
- **Frictionless Guest Mode (`Skip →`)**: Top-right frosted **Skip →** pill enters the app in Guest Mode without running unauthorized Firestore queries, surfacing clean sign-in upgrade prompts whenever cloud-only social features are accessed.

### 2. Universal Music Library & AI Discovery (`For You` & `Library`)
- **YouTube Music Catalog & Search (`YouTubeMusicClient`)**: Full YouTube Music (`WEB_REMIX`) catalog search, mood discovery, and direct YouTube / YouTube Music link resolution so users can search and play commercial, regional, Bollywood, and global tracks seamlessly.
- **Lazy On-Demand Stream Resolution**: Uses `NewPipeExtractor` + InnerTube multi-client extraction wired through Media3 `ResolvingDataSource` (`pulseyt://watch?v=<id>`) so expiring stream URLs are resolved dynamically on play/download with zero stale URL caching.
- **Audius Indie Catalog (`AudiusApiService`)**: Blends trending and searched tracks from Audius alongside YouTube Music and local files.
- **Gemini AI Curator**: Powered by **Firebase AI Logic** (`gemini-3.8-flash` / `gemini-3.5-flash-lite`) with App Check protection for natural-language AI DJ prompts, mood-matched feeds, and track vibe stories.
- **Behavioral Sonic DNA**: Tracks listening signals, skips, completions, and feed card dwell times in Room to compute a 6-axis **Sonic DNA** profile (*Energy, Valence, Tempo, Acoustic, Electronic, Chill*).

### 3. Live Soundscape Studio & Media3 Playback
- **Background Playback Service**: `Media3 ExoPlayer` + `MediaSessionService` (`PulseMediaService`) for lock-screen & notification controls.
- **Hardware Audio DSP (`EqualizerControlPanel`)**: 5-band `Equalizer`, `BassBoost`, and `PresetReverb` with preset modes and real-time morphing across soundscapes.
- **Dynamic Album Ambient Glow**: Extracts dominant colors (`AlbumColorExtractor`) from cover artwork to tint the Now Playing canvas smoothly.
- **Sleep Timer**: Configurable countdown timer with quick extend and cancel controls.

### 4. Synced Lyrics & Hindi → Hinglish Transliteration
- **Timed & Plain Lyrics**: Automatically fetches `[mm:ss.xx]` LRC synced and plain lyrics from **LRCLIB** (`LrclibApiService`) with manual track/artist retry support.
- **Automatic Hinglish Conversion (`HindiHinglishTransliterator`)**: Automatically detects Devanagari Hindi lyrics and transliterates them into readable **Hinglish** (Roman script) while keeping all other languages (English, Japanese, Korean, Spanish, Punjabi, etc.) in their native scripts.
- **Shared Lyric Notes**: Timestamped community notes pinned directly onto lyric lines when signed in.

### 5. Offline Room Vault & Local Audio Import
- **Room v2 Persistence (`PulseDatabase`)**: Durable local SQLite storage (`tracks`, `downloaded_tracks`, `listening_signals`) with schema migration (`MIGRATION_1_2`), indexed queries, SQLite `LIKE` search, and play counts.
- **Offline Audio & Artwork Caching (`OfflineDownloadManager`)**: Downloads playable audio tracks and cover artwork locally with startup integrity verification and cache management in Settings.
- **SAF Local & Cloud Drive Import (`SafMusicImporter`)**: Import local device audio files via Storage Access Framework (`OpenMultipleDocuments`) and batch-resolve metadata + lyrics (`LocalTrackMetadataResolver`).

### 6. Social Hub & Collaborative PulseRooms
- **Explicit Room Creation & Custom Naming**: Creating a listening room always opens a confirmation modal (`CreatePulseRoomDialog`) defaulting to `<Your Name>'s Room` (with editable room name and mood tag) and supports host rename, queue management, live reactions, and end/leave actions.
- **Sonic DNA Overlap & Taste Blends**: Compare 6-axis radar charts (`SonicDnaRadar`) with friends and generate 1-tap Taste Blend playlists.
- **Friend Feed, DMs & Collaborative Playlists**: Share tracks, soundscape presets, or lyric quotes to the public feed or direct messages, and upvote tracks in shared playlists.

### 7. Adaptive Material 3 Theming & Typography
- **Three Theme Modes**: **Obsidian Dark** (`#222831` slate palette), **Frosted Light** (electric mint & ultraviolet accents), and **System Auto**.
- **Custom Local Font Stack**: Bundled `.ttf` typography (`Space Grotesk`, `Plus Jakarta Sans`, `JetBrains Mono`).
- **Responsive Navigation**: Switches smoothly from bottom `NavigationBar` on phones to `NavigationRail` on tablets/expanded viewports (`>= 840.dp`).

---

## Architecture & Project Structure

PulseWave follows **MVVM + Repository** clean separation with unidirectional `StateFlow` UI state streams (`UiState<T>`).

```text
.
├── app/
│   ├── build.gradle.kts                  # App Gradle config, Compose, KSP, Room, Media3, Firebase
│   ├── google-services.json              # Firebase Android client config (replace with yours if forking)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml       # Permissions & PulseMediaService declaration
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt       # Single-activity Compose host, NavHost, Auth/Guest gate
│       │   │   ├── data/
│       │   │   │   ├── firebase/         # AuthHelper, AppCheckConfig, SocialRepository, FirestoreErrorHandler
│       │   │   │   ├── local/            # PulseDatabase (Room v2), LocalLibraryRepository, PreferencesRepository
│       │   │   │   ├── model/            # MusicModels, FirestoreModels
│       │   │   │   └── remote/           # AudiusApiService, LrclibApiService, OfflineDownloadManager, MetadataResolver
│       │   │   ├── domain/               # RecommendationEngine (Gemini), HindiHinglishTransliterator, AlbumColorExtractor, SafMusicImporter
│       │   │   ├── media/                # PlaybackController, PulseMediaService (Media3 + DSP EQ)
│       │   │   └── ui/
│       │   │       ├── components/       # PulseComponents, EqualizerControlPanel, SyncedLyricsDisplay, SonicDnaRadar, CreatePulseRoomDialog
│       │   │       ├── screens/          # AuthScreen, ForYouScreen, LibraryScreen, NowPlayingScreen, SocialHubScreen, PulseRoomScreen, SettingsScreen
│       │   │       ├── theme/            # Color, Theme (Obsidian Dark / Frosted Light), Type
│       │   │       └── viewmodel/        # PulseViewModel, SocialViewModel
│       │   └── res/                      # Drawables, adaptive launcher mipmaps, bundled fonts, XML configs
│       └── test/java/com/example/        # Unit & Robolectric tests (Room, transliterator, app tests)
├── gradle/libs.versions.toml             # Centralized Gradle Version Catalog
├── firestore.rules                       # Zero-trust Firestore security rules
├── firestore.test.js                     # Firebase Rules Unit Testing suite
├── firebase-blueprint.json               # Firestore schema blueprint
├── firebase.json                         # Firestore database & local emulator config
├── .env.example                          # Optional third-party env var template
└── README.md
```

---

## Tech Stack

| Layer | Libraries & Services |
| :--- | :--- |
| **UI & Navigation** | Jetpack Compose BOM `2024.09.00`, Material 3, Navigation Compose `2.8.9` (Type-Safe `@Serializable` routes), Coil `2.7.0` |
| **Audio & Media** | AndroidX Media3 ExoPlayer (`DefaultLoadControl`, `ResolvingDataSource`, `OkHttpDataSource` `1.6.1`), MediaSession, Android `Equalizer`, `BassBoost`, `PresetReverb` |
| **Local Storage** | Room `2.7.0` (KSP), DataStore Preferences `1.1.7`, Android Storage Access Framework (SAF) |
| **Networking** | Retrofit `2.12.0`, OkHttp `4.10.0`, Moshi `1.15.2`, Kotlinx Serialization `1.8.0` |
| **Cloud, Auth & AI** | Firebase BOM `34.17.0` (Auth, Firestore, AI Logic, App Check), Jetpack Credential Manager `1.5.0`, Google Identity Services |
| **External APIs & Extraction** | YouTube Music InnerTube (`WEB_REMIX`, `ANDROID_VR`, `TVHTML5`), rotating `X-Goog-Visitor-Id` sessions, Invidious/Piped proxy failover, `NewPipeExtractor v0.26.0`, Audius API, LRCLIB API |
| **Testing** | JUnit 4, Robolectric `4.16.1`, Kotlinx Coroutines Test `1.10.2`, Compose UI Test |

---

## Getting Started

### Prerequisites
- **Android Studio**: Narwhal / Meerkat or newer
- **JDK**: Java 11+ (AGP `9.1.1` / `compileSdk = 36` / `minSdk = 24`)
- **Firebase Project** *(optional for Guest Mode, required for Google Sign-In, Social Hub, PulseRooms & Gemini AI Logic)*

### 1. Clone the Repository
```bash
git clone https://github.com/<your-username>/PulseWave.git
cd PulseWave
```

### 2. Configure Environment & Firebase *(if using your own Firebase backend)*
1. Create a Firebase project and register an Android app with `applicationId`: `com.aistudio.pulsewave.kxmpzq` (or update `applicationId` in `app/build.gradle.kts`).
2. Enable **Authentication → Google Sign-In**, **Cloud Firestore**, **Firebase App Check**, and **Firebase AI Logic**.
3. Place your `google-services.json` inside `app/`.
4. Deploy Firestore rules using the Firebase CLI:
   ```bash
   firebase deploy --only firestore:rules
   ```
5. Copy `.env.example` to `.env` if you add any optional keys (no client-side Gemini API key is required since AI runs via Firebase AI Logic + App Check).

### 3. Build & Run
Open the project in **Android Studio**, allow Gradle sync to complete, and click **Run** on an Android device or emulator (`API 24+`), or build via CLI:

```bash
gradle :app:assembleDebug
```

---

## Testing

### Android Unit & Robolectric Tests
Runs local JVM unit tests, Room persistence & migration verification (`LocalLibraryRoomTest`), and Robolectric tests:
```bash
gradle :app:testDebugUnitTest
```

### Firestore Security Rules Tests
With the Firebase Emulator Suite installed:
```bash
firebase emulators:exec --only firestore "node --test firestore.test.js"
```

---

## Firestore Security Model

`firestore.rules` enforces strict authentication (`request.auth != null`), owner/participant checks, field allowlists, and string/numeric bounds across:
- `users/{uid}` & `users/{uid}/syncedLibrary/{trackId}`
- `friendRequests/{requestId}`
- `feedPosts/{postId}` & `feedPosts/{postId}/comments/{commentId}`
- `conversations/{conversationId}` & `conversations/{conversationId}/messages/{messageId}`
- `playlists/{playlistId}`
- `pulseRooms/{roomId}`, `queue/{itemId}`, & `reactions/{reactionId}`
- `lyricNotes/{noteId}`

---

## Notes for GitHub Push

- `.gitignore` excludes `local.properties`, `.env`, `debug.keystore`, `*.jks`, `*.apk`, `*.aab`, `.build-outputs/`, `.aistudio/`, `.gradle/`, `.kotlin/`, `.idea/`, and `build/` directories.
- Remember to keep sensitive release keystores and private `.env` credentials out of version control.
