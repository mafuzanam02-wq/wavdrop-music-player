# Wavdrop Music Player — Project Context

## Identity
| Field | Value |
|---|---|
| Product name | Wavdrop Music Player |
| Internal codename | Wavdrop |
| Package | `com.launchpoint.wavdrop` |
| Min SDK | 26 (Android 8.0 Oreo) |
| Target SDK | 35 |
| Version | 0.1.0-beta3.1 |

## Tech Stack
| Layer | Library |
|---|---|
| UI | Jetpack Compose, Material3 |
| Compose BOM | 2024.12.01 |
| DI | Hilt 2.52 (KSP) |
| Database | Room 2.6.1 (`WavdropDatabase` v7, `wavdrop.db`) |
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
│   │   ├── WavdropDatabase.kt         Room v7, exportSchema=true
│   │   ├── dao/                       SongDao, TrackStatsDao, ImportBaselineDao,
│   │   │                              PlaylistDao, TrackListenEventDao, LyricsOverrideDao
│   │   └── entity/                    SongEntity, TrackStatsEntity, ImportBaselineEntity,
│   │                                  PlaylistEntity, PlaylistSongEntity, TrackListenEventEntity,
│   │                                  LyricsOverrideEntity
│   ├── legacy/                        BlackPlayer EX import (parse → match → apply)
│   ├── backup/                        WavdropBackup JSON export/import system
│   ├── lyrics/                        Sidecar (.lrc) + ID3 embedded lyrics
│   ├── grouping/                      ArtistGrouper, AlbumGrouper
│   ├── library/                       FolderGrouper
│   ├── search/                        LibrarySearch, AlphabetIndex
│   ├── settings/                      LibraryScanSettings, HomeLayoutSettings,
│   │                                  StartupDestination (DataStore)
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
        ├── wrapped/                   WrappedScreen + WrappedViewModel
        ├── settings/                  SettingsScreen + SettingsViewModel
        ├── homecustomization/         HomeCustomizationScreen + HomeCustomizationViewModel
        ├── bpstatpreview/             BlackPlayer import preview + apply
        └── backupimport/              WavdropBackup import preview + apply
```

## Room Database — Schema v7

| Version | Migration | What changed |
|---|---|---|
| 1→2 | MIGRATION_1_2 | Add `track_stats` table |
| 2→3 | MIGRATION_2_3 | Add `import_baselines` table + index |
| 3→4 | MIGRATION_3_4 | Add `folderPath`, `folderName` columns to `songs` |
| 4→5 | MIGRATION_4_5 | Add `playlists` + `playlist_songs` tables |
| 5→6 | MIGRATION_5_6 | Add `track_listen_events` table + 2 indices |
| 6→7 | MIGRATION_6_7 | Add `lyrics_overrides` table + contentUri index |

### Entities
| Entity | Table | Key fields |
|---|---|---|
| `SongEntity` | `songs` | MediaStore-synced, folderPath/folderName |
| `TrackStatsEntity` | `track_stats` | playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite |
| `ImportBaselineEntity` | `import_baselines` | songId+sourceType+sourceKey PK; deduplicates re-imports |
| `PlaylistEntity` | `playlists` | AUTOINCREMENT, name, createdAt, updatedAt |
| `PlaylistSongEntity` | `playlist_songs` | playlistId+position PK, CASCADE delete |
| `TrackListenEventEntity` | `track_listen_events` | TYPE_PLAY/TYPE_SKIP; occurredAt, listenedMs, durationMs, source |
| `LyricsOverrideEntity` | `lyrics_overrides` | songId PK, contentUri, lyrics, updatedAt |

**Event history rule:** `TrackListenEventEntity` rows are written by Wavdrop playback
(`StatsTracker` -> `StatsRepository`) and by verified backup restore/import paths for real
portable Wavdrop events. Android restore uses `manual_restore` for restored Android backup
events, and Desktop-origin playback events keep `wavdrop_desktop_playback` after safe local
song matching. BlackPlayer aggregate imports never write events. History starts from DB
version 6.

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

**Effective listening time rule:** stored `totalListeningTimeMs` remains the actual
listening-time value used for database, backup, import, and export contracts. For display,
sorting, all-time reports, and aggregate summaries, Android derives
`effectiveListeningTimeMs` with `ListeningTimeRules.effectiveListeningTimeMs(...)`.
`estimatedListeningTimeMs` is `playCount × durationMs` when play count and duration are
available, with overflow guard; otherwise it is zero. `effectiveListeningTimeMs` is the
larger of stored `totalListeningTimeMs` and estimated listening time. The derived effective
value is used only for user-facing display, sorting, reports, and aggregate summaries. It
must never overwrite `totalListeningTimeMs`, appear as a backup/database field, or be treated
as measured playback time. Track Details, stats dashboard, all-time reports, artist insights,
and all-time aggregate fallback use effective listening time; event-backed Monthly Reports
and Wrapped continue to use actual listen-event `listenedMs`. Android and Desktop share this
rule while still exporting/importing only raw stored `totalListeningTimeMs`; no
`effectiveListeningTimeMs` backup field exists.

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
- Wrapped UI V1 uses `WrappedBuilder` directly, provides a year selector from available event
  years, and displays yearly totals/highlights without sharing or export.

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

### Lyrics
`LyricsRepository` read precedence:
1. App-managed user lyric override (`lyrics_overrides`)
2. Embedded ID3 SYLT/USLT lyrics
3. Same-folder `.lrc` sidecar
4. Same-folder `.txt` sidecar

`LyricsTextCleaner` strips timestamps. `LyricsDiagnostics` reports embedded/sidecar lookup
status. Editing is unsynced text only; Wavdrop does not write audio tags or fetch lyrics online.

## Home Dashboard
Section pinning via `HomeSectionId` enum (8 sections). `HomeLayoutSettingsRepository` persists
visible sections as `Set<String>` in DataStore. `HomeCustomizationScreen` is the settings entry
point. `LIBRARY_SHORTCUT` is always visible (non-toggleable). The optional Wrapped section shows a
compact link to the latest event-backed Wrapped year only when Wrapped data exists.

## Settings Screen
Route: `Screen.Settings`. Sections: Library (scan settings, import), Backup & Restore,
Statistics (Monthly Reports, Listening Reports), Appearance (Open app to, Home Sections), About.
Startup preference is stored in Preferences DataStore under `startup_destination`; default is
All Songs.

## BlackPlayer EX Import
Parse → match (title+artist+album, case-insensitive) → preview → apply via `db.withTransaction`.
Delta-based idempotent: `ImportBaselineDao` tracks last-imported counts; re-importing the same
file yields zero changes. **Never writes `TrackListenEventEntity` rows.**

## Backup & Restore
`WavdropBackupExporter` serialises stats + playlists to JSON. `WavdropBackupParser` +
`WavdropBackupStatsMatcher` + `WavdropBackupImportRepository` for Android-origin import.
`DesktopWavdropBackupParser` + `DesktopWavdropBackupImportPlanner` +
`DesktopWavdropBackupImportRepository` for desktop-origin import (stats, favorites,
playlists, and verified Desktop-origin listen events via metadata translation).

Desktop backups are detected by `appName: "wavdrop-desktop-lab"` or
`sourcePlatform: "desktop"`. Shared desktop backups carry Android identity fields
alongside these signals. Android accepts Desktop backups with `schemaVersion: 1`,
rejects unsupported explicit Desktop schema versions such as `99`, and tolerates
missing `schemaVersion` only for legacy compatibility. This parser hardening did
not introduce a backup schema bump or database schema change. Desktop song IDs are
strings; Android song IDs are Longs.
Desktop playlist entries use `songIds: string[]` and are translated to Android local
song IDs through metadata matching before insertion. Desktop-origin listen events also use
desktop-local string `songId` values, never Android IDs. Android resolves them through the
Desktop backup song mapping and safe metadata fallback, then stores matched events in
`track_listen_events` with local Android song IDs while preserving `occurredAt`,
`eventType`, `listenedMs`, `durationMs`, and `source = "wavdrop_desktop_playback"`.
Android does not require Android `contentUri` for Desktop-origin events and skips unmatched
events.

Imported listen events with impossible numeric values are skipped safely:
`occurredAt <= 0`, `listenedMs <= 0`, or `durationMs < 0`. Invalid listen events
are not imported, do not crash restore, do not poison the whole backup where safe
skipping is possible, and never cause synthetic replacement events to be created.
Valid listen events still restore normally, and repeat import idempotency remains
preserved.

Exportable listen-event sources are `wavdrop_playback`, `manual_restore`, and
`wavdrop_desktop_playback`. Synthetic/unsupported sources such as `blackplayer_import` and
unknown future sources remain excluded from export unless explicitly supported. Restored or
imported event idempotency uses local Android song ID + `occurredAt` + `eventType` +
`listenedMs`; do not dedupe only by song ID because multiple plays of the same song are
valid.

Import preview/result wording should make clear that Desktop backups may include stats,
favorites, playlists, and listening history, and that backup import does not modify audio
files. Backups contain metadata, history, settings, and playlist data only; they do not
contain music files.

Playlist import is conservative and non-destructive (Phase 1): matched songs are
appended, existing entries are not deleted or reordered, re-import is idempotent.
This is playlist portability, not two-way playlist synchronization.

## Shared Data Contract
Before touching backup, import, export, or migration code, read
`docs/WAVDROP_DATA_FORMAT_SPEC.md`, `docs/WAVDROP_BACKUP_SCHEMA_V1.md`, and
`docs/WAVDROP_IMPORT_RULES.md`. Android backups must keep the Wavdrop V1 identity
stable, treat song IDs as platform-local, merge aggregate stats safely, translate
playlist/event references to local song IDs, and never fabricate listening events from
aggregate imports. Backup/import/export must never modify audio files.
Android settings backup follows the platform-scoped preferences contract:
new exports write Android settings under `preferences.android`, Android ignores
`preferences.desktop`, and legacy flat `preferences` settings are import-only
backward compatibility.

**Validated Android/Desktop portability QA:** Desktop exported a backup with 732 songs and
1523 listen events, including 14 `wavdrop_desktop_playback` events. Android imported it,
exported 732 songs and 1525 listen events with the same 14 Desktop-origin events, imported
the same Desktop backup again, and exported 732 songs and 1525 listen events again. The
second import did not duplicate Desktop-origin events; `importBaselines` stayed 723,
`lyricsOverrides` stayed 28, playlists stayed 3, aggregate play counts/listening time did
not inflate, and no backup or database schema change was needed.

**Deferred beyond Beta 3.1:** Desktop portable import of `importBaselines`,
`lyricsOverrides`, and `preferences.android` beyond the current safe Android-side
behavior; a portable song identity layer or optional `portableSongKey`; partial audio
hashing or acoustic fingerprinting; backup schema v2; a shared cross-platform validation
library; and unknown future-field preservation architecture.

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
Official launcher icon: Obsidian Black (default at install)
Shipped icon choices (selectable via Settings → Appearance → App Icon):
- Obsidian Black (default)
- Midnight Violet
- Clean Purple
- Deep Teal
- Ocean Blue
- Sunset Orange

## Wrapped
`WrappedScreen` ← `WrappedViewModel` ← `WrappedBuilder`.
Entry point: Settings → Statistics → Wrapped. Year selector uses
`WrappedBuilder.availableYears(...)`, so only years with Wavdrop play/skip events appear.
V1 is intentionally restrained: total plays, listening time, top song/artist/album,
listening days, busiest day, most skipped track, and recent plays. Sharing/export is not
implemented yet, and imported aggregate counts are not used to create yearly history.

## Startup Screen Preference
`AppSettingsRepository` stores `StartupDestination` in the existing `wavdrop_preferences`
DataStore. `WavdropNavGraph` reads the preference once at graph creation and uses it as the
start destination: Home, Library, All Songs, Now Playing, or Settings. Default startup is
All Songs, and `StartupDestination.SONGS` routes to `Screen.Songs.route`. Existing in-app
navigation routes remain unchanged.

## Navigation / Home / Song Actions
Primary bottom navigation is Home, Songs, Library, and Settings only. Songs routes directly to
All Songs. Now Playing remains a direct route and is opened from mini-player/active playback entry
points.

Home is a compact dashboard rather than a full song-list duplicate. It emphasizes continue
listening, a recent-or-most-played preview, smart collections, and the Library shortcut.

All Songs rows keep double-tap favorite toggling but no longer show a visible heart action. Row
overflow menus provide Play, Play next, Add to queue, Toggle/Remove favorite, View stats, and View
folder when folder metadata is available. Favorite toggles show brief snackbar feedback. Search
result taps start playback at the selected song while preserving the full All Songs/library queue
order.

## Editable Lyrics Foundation
Track Details exposes "Edit lyrics" for unsynced lyrics. Saved lyrics are app-managed Room
overrides in `lyrics_overrides`; "Clear custom lyrics" deletes the override and reverts to
embedded/sidecar lookup. No ID3 tag writing, online lookup, synced lyrics, or karaoke/highlight
support is implemented.

## Not Yet Implemented
- Tag editing
- Cloud sync, streaming, equalizer
- Wrapped sharing and export
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
