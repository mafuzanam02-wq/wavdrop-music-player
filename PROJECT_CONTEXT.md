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
| Database | Room 2.6.1 (`WavdropDatabase` v6, `wavdrop.db`) |
| Preferences | DataStore 1.1.1 |
| Playback | Media3 1.4.1 / ExoPlayer |
| Image loading | Coil 2.7.0 |
| Navigation | Navigation Compose 2.8.5 |

## Architecture
Single-module MVVM + Repository.

```
app/src/main/kotlin/com/launchpoint/wavdrop/
├── WavdropApp.kt
├── MainActivity.kt
├── data/
│   ├── model/                         Domain models (Song, TrackStats, summaries, ...)
│   ├── local/
│   │   ├── WavdropDatabase.kt         Room v6, exportSchema=true
│   │   ├── dao/                       SongDao, TrackStatsDao, ImportBaselineDao,
│   │   │                              PlaylistDao, TrackListenEventDao
│   │   └── entity/                    SongEntity, TrackStatsEntity, ImportBaselineEntity,
│   │                                  PlaylistEntity, PlaylistSongEntity, TrackListenEventEntity
│   ├── legacy/                        BlackPlayer EX import (parse → match → apply)
│   ├── backup/                        WavdropBackup JSON export/import system
│   ├── lyrics/                        Sidecar (.lrc) + ID3 embedded lyrics
│   ├── grouping/                      ArtistGrouper, AlbumGrouper
│   ├── library/                       FolderGrouper
│   ├── search/                        LibrarySearch, AlphabetIndex
│   ├── settings/                      LibraryScanSettings, HomeLayoutSettings (DataStore)
│   ├── playback/                      PlaybackSessionSnapshot, PlaybackSessionRules,
│   │                                  PlaybackSessionRepository (resume-on-launch)
│   ├── playlists/                     PlaylistNameRules, PlaylistPositionRules
│   ├── smart/                         SmartCollectionBuilder
│   ├── stats/                         All analytics builders (see Analytics Layer section)
│   ├── artwork/                       ArtworkResolver
│   ├── mediastore/MediaStoreScanner.kt IS_MUSIC, ≥30 s
│   └── repository/                    SongRepository, StatsRepository,
│                                      SmartCollectionRepository
├── di/AppModule.kt, LyricsModule.kt
├── playback/
│   ├── PlaybackService.kt             MediaSessionService, ExoPlayer + MediaSession
│   ├── PlayerController.kt            @Singleton, queue, shuffle, repeat, seek
│   ├── StatsTracker.kt                meaningful-play threshold, event write
│   ├── NowPlayingState.kt             queue, position, duration, shuffle, repeatMode
│   ├── QueueNavigator.kt              next/prev/restart logic
│   └── RepeatMode.kt                  OFF / ONE / ALL
└── ui/
    ├── theme/, permission/
    ├── navigation/WavdropNavGraph.kt
    ├── components/                    SongRow, ArtworkImage, SearchTopAppBar,
    │                                  AlphabetSideIndex, AddToPlaylistDialog
    ├── viewmodel/PlaybackControlsViewModel.kt
    └── screen/
        ├── home/                      HomeScreen + HomeViewModel (dashboard)
        ├── nowplaying/                NowPlayingScreen, NowPlayingViewModel
        ├── trackdetails/              TrackDetailsScreen, TrackDetailsViewModel
        ├── artists/, albums/, folders/ Browse screens + ViewModels
        ├── playlists/                 PlaylistsScreen, PlaylistDetailsScreen,
        │                             AddSongsToPlaylistScreen
        ├── smart/                     SmartCollectionsScreen, SmartCollectionDetailsScreen
        ├── statistics/                StatisticsScreen + StatisticsViewModel
        ├── monthlyreports/            MonthlyReportsScreen + MonthlyReportsViewModel
        ├── settings/                  SettingsScreen + SettingsViewModel
        ├── homecustomization/         HomeCustomizationScreen + HomeCustomizationViewModel
        ├── bpstatpreview/             BlackPlayer import preview + apply
        └── backupimport/              WavdropBackup import preview + apply
```

## Room Database — Schema v6

| Version | Migration | What changed |
|---|---|---|
| 1→2 | MIGRATION_1_2 | Add `track_stats` table |
| 2→3 | MIGRATION_2_3 | Add `import_baselines` table + index |
| 3→4 | MIGRATION_3_4 | Add `folderPath`, `folderName` columns to `songs` |
| 4→5 | MIGRATION_4_5 | Add `playlists` + `playlist_songs` tables |
| 5→6 | MIGRATION_5_6 | Add `track_listen_events` table + 2 indices |

### Entities
| Entity | Table | Key fields |
|---|---|---|
| `SongEntity` | `songs` | MediaStore-synced, folderPath/folderName |
| `TrackStatsEntity` | `track_stats` | playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite |
| `ImportBaselineEntity` | `import_baselines` | songId+sourceType+sourceKey PK; deduplicates re-imports |
| `PlaylistEntity` | `playlists` | AUTOINCREMENT, name, createdAt, updatedAt |
| `PlaylistSongEntity` | `playlist_songs` | playlistId+position PK, CASCADE delete |
| `TrackListenEventEntity` | `track_listen_events` | TYPE_PLAY/TYPE_SKIP; occurredAt, listenedMs, durationMs, source |

**Event history rule:** `TrackListenEventEntity` rows are written only by Wavdrop playback
(`StatsTracker` → `StatsRepository`). BlackPlayer imports and JSON restore paths NEVER write
events. History starts from DB version 6.

## Permission Flow
- API 33+: `READ_MEDIA_AUDIO` · API 26–32: `READ_EXTERNAL_STORAGE`
- States: `NotRequested → Denied → PermanentlyDenied → Granted`
- `syncIfNeeded()` triggered via `LaunchedEffect` on grant

## Playback Architecture
- `PlaybackService` owns ExoPlayer + MediaSession; `foregroundServiceType=mediaPlayback`
- `PlayerController` (@Singleton): queue management, shuffle, repeat, seek, 500ms position ticker
- `NowPlayingState`: song, isPlaying, positionMs, durationMs, queue, currentIndex, shuffleEnabled, repeatMode
- `StatsTracker`: meaningful-play threshold = min(30s, duration/2); writes `recordPlay`/`recordSkip`
- `QueueNavigator`: next/previous/restart logic, shuffle-aware
- `PlaybackSessionRepository`: persists last-played context for resume-on-launch

## Statistics Engine

### Aggregate (TrackStatsEntity)
All-time counters written on every play/skip since app install.

| Builder | Input | Output | Use |
|---|---|---|---|
| `StatsDashboardBuilder` | songs + stats | `StatsDashboardSummary` | Statistics screen overview |
| `ListeningReportBuilder` | songs + stats | `ListeningReportSummary` | All-time listening report |
| `ArtistInsightsBuilder` | songs + stats | `ArtistInsightsSummary` | Per-artist detail screen |
| `MostPlayedBuilder` | songs + stats + events + period | `List<SongStatsSummary>` | Most-Played tab (ALL_TIME / THIS_MONTH) |

### Event-backed (TrackListenEventEntity)
Per-play/per-skip rows since DB v6 upgrade. Accurate time-scoped analytics.

| Builder | Input | Output | Use |
|---|---|---|---|
| `ListeningAnalyticsBuilder` | range + songs + stats* + events | `ListeningPeriodSummary` | Monthly reports, yearly, Wrapped |
| `MonthlyReportBuilder` | month + songs + stats + events | `MonthlyReportSummary` | Monthly Reports screen |
| `WrappedBuilder` | year/range + songs + events | `WrappedSummary` | Wrapped foundation |

*stats accepted for call-shape stability; ignored for period-scoped totals.

### Analytics Models

**`ListeningPeriodRange`** — inclusive epoch-ms window  
Factories: `month(year, month, zone)`, `year(year, zone)`, `allTime(zone)`  
`contains(epochMs)` for fast range check; `zone` stored for day-level date math.

**`ListeningPeriodSummary`** — output of `ListeningAnalyticsBuilder`
```
totalPlayCount, totalSkipCount, totalListeningTimeMs
tracksPlayedCount, artistsPlayedCount, albumsPlayedCount
topSongs, topArtists, topAlbums (up to topListLimit each, default 10)
mostSkippedTrack, recentlyPlayed
listeningDaysCount, busiestDay (LocalDate?), busiestDayPlayCount
averagePlaysPerActiveDay
emptyState: ListeningAnalyticsEmptyState
hasActivity, uniqueSongsPlayedCount, uniqueArtistsPlayedCount, uniqueAlbumsPlayedCount
```

**`ListeningAnalyticsEmptyState`**  
`reason`: `HAS_ACTIVITY | NO_EVENTS_IN_RANGE | ONLY_ORPHAN_EVENTS | NO_AGGREGATE_ACTIVITY`  
`hasEventsInRange`, `hasMatchedLibraryItems`, `isEmpty`

**`ListeningAnalyticsBuilder` methods**
- `build(range, songs, stats, events, topListLimit=10)` — event-backed, stats ignored for period totals
- `buildAllTimeAggregateFallback(songs, stats, topListLimit, zone)` — aggregate path for all-time views

**`MonthlyReportSummary` accuracy modes**
- `EVENT_BACKED`: event rows exist for the month -> accurate monthly counts
- `NO_EVENT_HISTORY`: no event rows exist for the month -> zero monthly counts/lists

**`MostPlayedPeriod`**: `ALL_TIME` (stats) | `THIS_MONTH` (events via ListeningPeriodRange)

**Wrapped foundation**
- `WrappedPeriod`: yearly period wrapper around `ListeningPeriodRange.year(...)`.
- `WrappedSummary`: event-backed yearly/period summary with top songs, artists, albums, listening
  days, busiest day, average plays per active day, recent plays, skipped track, and empty state.
- `WrappedBuilder.buildYear(...)` / `buildPeriod(...)`: delegates to `ListeningAnalyticsBuilder.build(...)`
  with no aggregate fallback.
- `WrappedBuilder.availableYears(...)`: discovers years from event history only.

## Library Features

### Songs / Browse
- `SongRepository`: MediaStore sync via `MediaStoreScanner`
- Artist browse: `ArtistGrouper`, `ArtistsScreen/ViewModel`, `AlbumDetailsScreen/ViewModel`
- Album browse: `AlbumGrouper`, `AlbumsScreen/ViewModel`
- Folder browse: `FolderGrouper`, `FoldersScreen/FolderDetailsScreen`
- `LibrarySearch`: cross-field (title + artist + album), case-insensitive
- `AlphabetIndex`: fast-scroll side index
- `LibraryScanSettings`: include/exclude folders, minimum duration (DataStore)

### Playlists
Room v5. `PlaylistEntity` + `PlaylistSongEntity` (cascade delete, position-based ordering).
`PlaylistNameRules` + `PlaylistPositionRules`. `AddSongsToPlaylistScreen`.

### Smart Collections
8 read-only types: FAVORITES, MOST_PLAYED, RECENTLY_PLAYED, NEVER_PLAYED, RECENTLY_ADDED,
MOST_SKIPPED, LONG_TRACKS, SHORT_TRACKS. Built by `SmartCollectionBuilder` from songs + stats.
No schema change — computed on demand.

### Lyrics (Phase 1 — complete, do not reopen)
`LyricsRepository` → `SidecarLyricsExtractor` (.lrc sidecar) + ID3 embedded SYLT/USLT tags.
`LyricsTextCleaner` strips timestamps. `LyricsDiagnostics` for debug.

## Home Dashboard
Section pinning via `HomeSectionId` enum (7 sections). `HomeLayoutSettingsRepository` persists
visible sections as `Set<String>` in DataStore. `HomeCustomizationScreen` is the settings entry
point. `LIBRARY_SHORTCUT` is always visible (non-toggleable).

## Settings Screen
Route: `Screen.Settings`. Sections: Library (scan settings, import), Backup & Restore,
Statistics (Monthly Reports, Listening Reports), Appearance (Home Sections), About.

## BlackPlayer EX Import
Parse → match (title+artist+album, case-insensitive) → preview → apply via `db.withTransaction`.
Delta-based idempotent: `ImportBaselineDao` tracks last-imported counts; re-importing the same
file yields zero changes. **Never writes `TrackListenEventEntity` rows.**

## Backup & Restore
`WavdropBackupExporter` serialises stats + playlists to JSON. `WavdropBackupParser` + 
`WavdropBackupStatsMatcher` + `WavdropBackupImportRepository` for import.

## Monthly Reports
`MonthlyReportsScreen` ← `MonthlyReportsViewModel` ← `MonthlyReportBuilder`.
Entry point: Settings → Statistics → Monthly Reports. Month selector with prev/next arrows.
V2 is event-backed only: available months come from `TrackListenEventEntity` PLAY/SKIP rows,
and `MonthlyReportBuilder` maps directly from `ListeningAnalyticsBuilder.build(...)`.
Monthly reports expose plays, skips, listening time, top songs/artists/albums, listening days,
busiest day, average plays per active day, and `emptyState.reason`.
`TrackStatsEntity` and imported BlackPlayer aggregate counts are not used to fake monthly history.

## Naming History
`Lyra` → `EchoVault` → **Wavdrop** (final, do not rename again)

## Branding
Official launcher icon: Midnight Violet  
Approved alternates: Clean Purple, Deep Teal  
Future: user-selectable icons via activity-alias (Settings → Appearance)

## Not Yet Implemented
- Theme switching / dark mode toggle
- Tag editing
- Cloud sync, streaming, equalizer
- Wrapped UI, sharing, and export
- Listening trends / streak tracking
- Smart playlists (editable, rule-based)
- filePath-based BlackPlayer matching (requires ContentResolver)
- JSON export/import UI (backend exists, UI disabled)

## Listening Analytics Architecture

Shared pure analytics lives in `data/stats/ListeningAnalyticsBuilder.kt`.

Core models:
- `ListeningPeriodRange`: inclusive epoch-ms range for month, year, all-time, or custom periods.
- `ListeningPeriodSummary`: reusable output for reports, Most Played, future Wrapped views, and trends.
- `ListeningAnalyticsEmptyState`: metadata for no events, orphan-only events, and aggregate-empty states.

Data-source rules:
- Time-period analytics use `TrackListenEventEntity` only.
- Monthly and historical analytics must not infer activity from aggregate `TrackStatsEntity` rows.
- BlackPlayer imports and legacy aggregate stats remain aggregate-only unless real event rows exist.
- `TrackStatsEntity` is used only through explicit all-time aggregate fallback APIs such as
  `ListeningAnalyticsBuilder.buildAllTimeAggregateFallback(...)`.

Current consumers:
- Monthly reports delegate every requested month to `ListeningAnalyticsBuilder.build(...)` and keep
  aggregate stats out of monthly history.
- Most Played V2 delegates All Time to the aggregate fallback and This Month to event-backed month analytics.

No Room schema change is required for the shared analytics layer.
