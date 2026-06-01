# Wavdrop Music Player — Project Context

## Identity
| Field | Value |
|---|---|
| Product name | Wavdrop Music Player |
| Internal codename | Wavdrop |
| Package | `com.launchpoint.wavdrop` |
| Min SDK | 26 (Android 8.0 Oreo) |
| Target SDK | 35 |
| Version | 0.1.0 |

## Tech Stack
| Layer | Library |
|---|---|
| UI | Jetpack Compose, Material3 |
| Compose BOM | 2024.12.01 |
| DI | Hilt 2.52 (KSP) |
| Database | Room 2.6.1 (`WavdropDatabase`, `wavdrop.db`) |
| Preferences | DataStore 1.1.1 |
| Playback | Media3 1.4.1 / ExoPlayer (wired — see Playback section) |
| Image loading | Coil 2.7.0 |
| Navigation | Navigation Compose 2.8.5 |

## Architecture
Single-module MVVM + Repository.

```
app/src/main/kotlin/com/launchpoint/wavdrop/
├── WavdropApp.kt
├── MainActivity.kt
├── data/
│   ├── model/Song.kt                      uri = content:// URI
│   ├── local/
│   │   ├── WavdropDatabase.kt             Room, exportSchema = true
│   │   ├── dao/SongDao.kt
│   │   └── entity/SongEntity.kt
│   ├── mediastore/MediaStoreScanner.kt    IS_MUSIC, ≥30 s
│   └── repository/SongRepository.kt       sync() + Flow<Song>
├── di/AppModule.kt                        Room DB + DAO
├── playback/
│   ├── PlaybackService.kt                 MediaSessionService, owns ExoPlayer + MediaSession
│   ├── PlayerController.kt               @Singleton, wraps MediaController async connection
│   └── NowPlayingState.kt                 data class (song, isPlaying)
└── ui/
    ├── theme/{Color,Theme,Type}.kt        WavdropTheme, WavdropTypography
    ├── navigation/WavdropNavGraph.kt
    ├── permission/AudioPermission.kt      AudioPermissionStatus enum + helpers
    └── screen/home/
        ├── HomeScreen.kt                  song list (clickable) + NowPlayingBar bottom bar
        └── HomeViewModel.kt               syncIfNeeded, playSong, togglePlayPause
```

## Permission Flow (implemented)
- API 33+: `READ_MEDIA_AUDIO` · API 26–32: `READ_EXTERNAL_STORAGE`
- States: `NotRequested → Denied → PermanentlyDenied → Granted`
- `syncIfNeeded()` called via `LaunchedEffect` once permission is `Granted`
- Permanently denied: deep-links to system app settings

## Playback Architecture (implemented — foundation)
- `PlaybackService` (`MediaSessionService`) owns a single `ExoPlayer` + `MediaSession`
  - `AudioAttributes` set to USAGE_MEDIA / CONTENT_TYPE_MUSIC with audio focus handling
  - `handleAudioBecomingNoisy = true` (headphone disconnection)
  - Registered in Manifest with `foregroundServiceType="mediaPlayback"`
- `PlayerController` (`@Singleton`) wraps the async `MediaController` connection
  - Connects to `PlaybackService` via `SessionToken` + `MediaController.Builder.buildAsync()`
  - Queues songs received before connection completes (`pendingSong`)
  - `Player.Listener.onIsPlayingChanged` updates `StateFlow<NowPlayingState>`
  - Methods: `playSong(Song)`, `togglePlayPause()`, `release()`
- `HomeViewModel` injects `PlayerController`, exposes `nowPlayingState: StateFlow<NowPlayingState>`
- `HomeScreen` renders `NowPlayingBar` as Scaffold `bottomBar` (hidden when no song loaded)
- Each `SongRow` is clickable → calls `viewModel.playSong(song)`

## Current Playback Limitations
- Single song only — no queue, no next/previous
- No notification customization (default Media3 notification)
- No seek bar in the mini-player
- No album art loading (placeholder only)
- `PlayerController.release()` is not called from app lifecycle (acceptable for MVP)

## What Is NOT Yet Implemented
- Queue / playlist management
- Album / Artist / Playlist screens
- Seek bar / progress in Now Playing
- Tag editing, lyrics, cloud sync, streaming
- Equalizer, statistics

## Naming History
`Lyra` → `EchoVault` → **Wavdrop** (final, do not rename again)
