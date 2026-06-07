# Wavdrop Play Store Readiness Checklist

Tracks every item needed before submitting to Google Play. Update each row
as items are completed. Tick `[x]` when done; leave `[ ]` open.

---

## 1. Build Configuration

| Item | Status | Notes |
|---|---|---|
| `compileSdk = 35` | ✅ Set | `app/build.gradle.kts` |
| `targetSdk = 35` | ✅ Set | Meets Google Play's current minimum requirement |
| `minSdk = 26` (Android 8.0) | ✅ Set | Covers a wide device range |
| `versionCode` set to production value | ⬜ Pending | Currently `1`; must be unique per Play Console upload |
| `versionName` set to release value | ✅ `"0.1.0"` | Appropriate for initial/beta track |
| Release build signed with production keystore | ⬜ Pending | Generate and store keystore securely before upload |
| ProGuard/R8 enabled for release build | ✅ `isMinifyEnabled = true`, `isShrinkResources = true` | Confirmed in `app/build.gradle.kts` |
| Debug APK removed from release track | ⬜ Pending | Only upload a release-signed APK/AAB |
| AAB (Android App Bundle) built for upload | ⬜ Pending | Play Console prefers `.aab` over `.apk` |

---

## 2. Permissions

| Permission | Declared | Appropriate | Notes |
|---|---|---|---|
| `READ_EXTERNAL_STORAGE` | ✅ | ✅ | `maxSdkVersion="32"` — correct scope for API 26–32 |
| `READ_MEDIA_AUDIO` | ✅ | ✅ | API 33+ granular audio permission |
| `FOREGROUND_SERVICE` | ✅ | ✅ | Required for background media playback |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | ✅ | ✅ | Matches `foregroundServiceType=mediaPlayback` in service |
| `INTERNET` | ✅ Absent | ✅ | App makes no network requests |
| Location | ✅ Absent | ✅ | Not used |
| Camera | ✅ Absent | ✅ | Not used |
| Microphone | ✅ Absent | ✅ | Not used |
| Contacts | ✅ Absent | ✅ | Not used |
| Advertising ID | ✅ Absent | ✅ | No ads or tracking |

---

## 3. Privacy / Data Safety

Google Play Data Safety form — all answers are derived from the code audit.
No data leaves the device except via user-initiated file export or share sheet.

| Question | Answer | Evidence |
|---|---|---|
| Does the app collect user data and send it to your servers? | **No** | No `INTERNET` permission; no networking libraries |
| Does the app share data with third parties? | **No** | Share sheet is user-initiated OS feature; no data pipeline |
| Is data encrypted in transit? | **N/A** | No data in transit |
| Does the app use advertising ID? | **No** | No ad SDK; `INTERNET` absent |
| Does the app use crash reporting? | **No** | No Crashlytics, Firebase, or equivalent |
| Can users request data deletion? | **Yes** | Uninstalling the app deletes all local DB and DataStore data |
| Privacy Policy URL added to Play Console listing | ⬜ Pending | Publish at `https://launchpointdigital.co.za/wavdrop/privacy` |
| Privacy Policy URL added to app store listing | ⬜ Pending | Same URL in the listing Privacy Policy field |

**Data Safety — detailed data type declarations:**

Select "No" for all data type collection in the Data Safety section. Specific
types to explicitly confirm as not collected:

- Location — not collected
- Personal info (name, email) — not collected
- Financial info — not collected
- Health and fitness — not collected
- Messages — not collected
- Photos and videos — not collected (audio file metadata is read locally, not collected)
- Audio files — accessed locally via MediaStore; not collected/uploaded
- Files and docs — backup files created by user via SAF; Wavdrop does not upload them
- Contacts — not collected
- App activity — not collected
- App info and performance — not collected (no crash reporting)
- Device or other IDs — not collected

---

## 4. In-App Legal / Privacy Wording

| Item | Status | Location |
|---|---|---|
| Privacy Policy dialog present | ✅ Done | Settings → About → Legal → Privacy Policy |
| Privacy Policy covers local storage, permissions, backup, share, delete, import | ✅ Done | `WavdropAbout.PRIVACY_POLICY` — 9 paragraphs |
| No cloud/future-sync promise in Privacy Policy | ✅ Done | Removed in legal polish pass |
| Terms of Use dialog present | ✅ Done | Settings → About → Legal → Terms of Use |
| Disclaimer covers delete permanence, share, music rights | ✅ Done | `WavdropAbout.DISCLAIMER` — 8 items |
| Open Source Licenses: no placeholder text | ✅ Done | Apache 2.0 attribution for all 8 major dependencies |
| Backup export subtitle accurate | ✅ Done | `SettingsBackupScreen.kt` |
| Backup import subtitle accurate (restore is live) | ✅ Done | `SettingsBackupScreen.kt` |
| Permission request wording clear | ✅ Done | `AudioPermissionGate.kt` — "audio files on this device" |
| Onboarding privacy promise present | ✅ Done | Page 2: "Your music stays yours" |

---

## 5. Manifest / Configuration

| Item | Status | Notes |
|---|---|---|
| `android:label` set | ✅ `@string/app_name` = "Wavdrop Music Player" | |
| Launcher icon references valid mipmap-nodpi assets | ✅ | All 6 aliases point to `mipmap-nodpi/wavdrop_icon_*.png` |
| Round icon references valid | ✅ | `wavdrop_icon_*_round.png` present for all 6 variants |
| `ACTION_VIEW` audio MIME types declared | ✅ | `audio/*`, `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/flac`, `audio/ogg`, `audio/wav`, `audio/opus`, `audio/amr`, `audio/midi` |
| `android:allowBackup="false"` | ✅ | Prevents ADB backup of app data |
| `PlaybackService` exported with media session action | ✅ | Required for system media UI on API 33+ |
| `foregroundServiceType=mediaPlayback` declared | ✅ | Matches `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission |
| `android:supportsRtl="true"` | ✅ | Required for international distribution |
| 6 launcher aliases declared | ✅ | `MidnightViolet` enabled; others disabled until user selects |

---

## 6. Store Listing Assets

| Asset | Status | Spec |
|---|---|---|
| App icon (512 × 512 px, PNG, no alpha) | ⬜ Pending | Required for listing. Use Midnight Violet icon as base |
| Feature graphic (1024 × 500 px, JPG or PNG, no alpha) | ⬜ Pending | Required — appears at the top of listing on large screens |
| Phone screenshots (minimum 2, portrait) | ⬜ Pending | See Section 7 |
| Tablet screenshots | ⬜ Optional | Recommended if targeting tablets |
| Short description (≤ 80 chars) | ⬜ Pending | See `PLAY_STORE_LISTING_DRAFT.md` |
| Full description (≤ 4000 chars) | ⬜ Pending | See `PLAY_STORE_LISTING_DRAFT.md` |
| Privacy Policy URL | ⬜ Pending | `https://launchpointdigital.co.za/wavdrop/privacy` |
| Contact email | ⬜ Pending | Confirm final support email address before listing |
| Developer name | ⬜ Pending | Must match Play Console account name (LaunchPoint Digital) |

---

## 7. Screenshot Checklist

Capture on a clean device with a representative music library loaded.
Minimum 2 phone screenshots required; 6–8 recommended.

| Screen | Priority | Notes |
|---|---|---|
| Home dashboard | High | Show continue listening + sections |
| Now Playing | High | With album art, controls, track info visible |
| Songs list (All Songs) | High | Shows library scan working |
| Albums or Artists | Medium | Shows browse capability |
| Statistics Dashboard or Listening Report | Medium | Shows Wrapped/reports value |
| Playlists or Smart Collections | Medium | Shows organisation features |
| Settings → About (Legal section visible) | Low | Demonstrates transparency |
| Monthly Reports or Wrapped | Low | Shows unique analytics value |

---

## 8. Closed Testing / Internal Testing

| Item | Status | Notes |
|---|---|---|
| Internal test track created in Play Console | ⬜ Pending | Use for initial upload before promotion |
| APK/AAB uploaded to internal/closed track | ⬜ Pending | |
| Minimum 1 tester confirmed install and launch | ⬜ Pending | |
| QA smoke pass completed | ⬜ Pending | See `SOFT_LAUNCH_QA_CHECKLIST.md` |
| No P0/P1 crashes in closed track | ⬜ Pending | |
| Test devices: Samsung (One UI) | ⬜ Pending | Launcher icon caching; share sheet behavior |
| Test devices: Pixel / stock Android | ⬜ Pending | Reference behavior |
| Test devices: Android 8.x / 9 (minSdk) | ⬜ Pending | Confirm `READ_EXTERNAL_STORAGE` path |
| Test devices: Android 13+ | ⬜ Pending | Confirm `READ_MEDIA_AUDIO` path |

---

## 9. Known Device / QA Risk Areas

| Area | Risk | Mitigation |
|---|---|---|
| Launcher icon switching | Launcher caching delays vary by OEM | Documented in `PLANNED.md`; explain delay in-app if needed |
| Share action on Samsung / Xiaomi | OEM share sheets vary | Test `audio/*` MIME type across major OEMs |
| Delete from device on Android 11–12 vs 13+ | `MediaStore.createDeleteRequest` behavior differs | Test on each API level; confirm system dialog appears |
| Bluetooth auto-resume | OEM audio routing behavior varies | Real-device testing required; documented in QA checklist |
| WhatsApp voice note exclusion | Folder path matching depends on OEM folder structure | Test on WhatsApp-installed device |
| Large library performance (5,000+ songs) | LazyColumn re-composition cost at scale | Stress test before production release |
| Backup restore with very large event history | Memory and time to parse large JSON | Tested manually; no known issue |

---

## 10. Release Blockers

Items that must be resolved before any public release track.

| Blocker | Owner | Notes |
|---|---|---|
| Public Privacy Policy URL | LaunchPoint Digital web team | `https://launchpointdigital.co.za/wavdrop/privacy` — page not yet live |
| Production keystore generated and stored securely | Dev | Do not lose; required for all future updates |
| Release-signed AAB built and tested | Dev | |
| Store listing assets complete (icon, feature graphic, screenshots) | Design | |
| Data Safety form completed in Play Console | Dev | All "No" for data collection; see Section 3 |
| Privacy Policy URL entered in Play Console listing | Dev | |
| App reviewed against Play Store policies for audio players | Dev | Review current play.google.com/about/developer-content-policy |

---

## 11. Post-Launch Items (Not Blockers)

| Item | Notes |
|---|---|
| Migrate support email to domain address | `hello.launchpointdigital@gmail.com` → `support@launchpointdigital.co.za` (Under Evaluation in `PLANNED.md`) |
| Add Privacy Policy URL reference to in-app Privacy Policy dialog | Once web page is live, add a "Full policy at [URL]" line to `WavdropAbout.PRIVACY_POLICY` |
| Review OSS licenses via Gradle OSS Licenses Plugin | Current manual Apache 2.0 list is sufficient for launch; plugin automates future dependency additions |
| Tablet layout review | Compose stretches gracefully but a tablet-specific layout pass would improve quality |
