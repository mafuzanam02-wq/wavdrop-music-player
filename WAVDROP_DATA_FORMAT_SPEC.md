\# WAVDROP\_DATA\_FORMAT\_SPEC.md



\# Wavdrop Data Format Specification



Version: 1.0



Status: Active



Purpose:

Define the data exchange contract between all Wavdrop platforms.



This specification exists so Android, Desktop, and future Wavdrop platforms can exchange user data safely while preserving user ownership and preventing platform drift.



\---



\# Core Principles



Wavdrop data exchange must remain:



\* Offline-first

\* Local-first

\* Human-readable

\* Versioned

\* Backward-compatible where practical

\* Account-free

\* Server-free



No platform should require internet access to import or export user data.



\---



\# Supported Platforms



Current:



\* Wavdrop Android

\* Wavdrop Desktop



Future:



\* Wavdrop Desktop Stable

\* Wavdrop Lab

\* Additional Wavdrop platforms



\---



\# Data Ownership Rules



User data belongs to the user.



Platforms may:



\* Export data

\* Import data

\* Merge data



Platforms must not:



\* Encrypt data unnecessarily

\* Lock data behind accounts

\* Require cloud services

\* Require subscriptions



\---



\# Backup Versioning



Every backup must include:



```json

{

&#x20; "schemaVersion": 1

}

```



Future versions must increment schemaVersion.



Importers should reject unsupported major schema changes safely.



\---



\# Required Backup Metadata



```json

{

&#x20; "schemaVersion": 1,

&#x20; "exportedAt": "ISO-8601 timestamp",

&#x20; "appName": "platform identifier"

}

```



Examples:



```json

{

&#x20; "appName": "wavdrop"

}

```



```json

{

&#x20; "appName": "wavdrop-desktop-lab"

}

```



\---



\# Song Identity



Song IDs are platform-local.



Song IDs must never be assumed portable between platforms.



Example:



Android ID:



```text

song\_123

```



Desktop ID:



```text

47d323c937cf...

```



These IDs are not expected to match.



Importers must never use foreign-platform IDs for matching.



\---



\# Song Matching Rules



Platforms must match songs using metadata.



Preferred order:



1\. Title + Artist + Album

2\. Title + Artist

3\. Future matching tiers



Matching uses comparison-only normalization.



Display metadata must remain unchanged.



\---



\# Metadata Normalization Rules



Normalization is used only for matching.



Display values must never be modified.



Normalization should handle:



\* Case differences

\* Accent differences

\* Apostrophe differences

\* Unicode variations

\* Filename cleanup variations



Examples:



```text

Jolé

Jole

```



May match.



```text

Don't Stop

Dont Stop

```



May match.



Display text remains unchanged.



\---



\# Ambiguous Match Rule



If multiple songs match:



```text

Desktop Song

&#x20; ↓

Android Song A

Android Song B

```



Import must skip the song.



Importer should report:



```text

Ambiguous Match

```



No automatic selection.



\---



\# Missing Match Rule



If no song matches:



```text

Desktop Song

&#x20; ↓

No Android Song

```



Importer must skip.



No song creation.



No metadata creation.



No placeholder entries.



\---



\# Statistics Merge Rules



Statistics are cumulative values.



Merge strategy:



MAX



Never additive.



\---



\## Play Count



Rule:



```text

mergedPlayCount =

max(localPlayCount, importedPlayCount)

```



Example:



```text

Local: 70

Imported: 90

Result: 90

```



\---



\## Listening Time



Rule:



```text

mergedListeningTime =

max(localListeningTime, importedListeningTime)

```



Example:



```text

Local: 4h

Imported: 6h

Result: 6h

```



\---



\## Last Played



Rule:



Latest timestamp wins.



```text

mergedLastPlayed =

max(localTimestamp, importedTimestamp)

```



\---



\## Favorites



Rule:



```text

localFavorite OR importedFavorite

```



Example:



```text

false + true

→ true

```



\---



\# Listening History Rule



Aggregate statistics:



\* may be merged



Listening events:



\* must not be fabricated



Example:



Imported desktop play counts must not create:



```text

PLAY events

SKIP events

```



Monthly Reports and Wrapped remain based on real recorded listening events.



\---



\# Idempotency Requirement



Repeated imports must be safe.



Example:



Import backup

Import same backup again



Result:



No stat inflation

No duplicate growth

No duplicated favorites



Imports must converge.



\---



\# Playlist Rules



Current status:



Reserved for future specification.



Future support:



\* Playlist import

\* Playlist export

\* Playlist merge

\* M3U interoperability



\---



\# Lyrics Rules



Current status:



Reserved for future specification.



Future support:



\* Lyrics export

\* Lyrics import

\* Conflict resolution



\---



\# Future Conflict Resolution



Some fields cannot safely use MAX merge.



Examples:



\* Ratings

\* Lyrics edits

\* Notes

\* Tags

\* Playlist ordering



These fields will require:



\* timestamps

\* conflict policies

\* user choice



Future versions of this specification will define those behaviors.



\---



\# Compatibility Goal



A user should be able to move between Wavdrop platforms without losing:



\* Play counts

\* Listening time

\* Favorites

\* Settings (where supported)

\* Playlists (future)

\* Lyrics (future)



while remaining fully offline and in control of their own data.



