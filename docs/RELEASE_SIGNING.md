# Wavdrop Release Signing (WD-06)

Status: **scaffold in place, credentials pending.** No keystore exists yet; the
release build is intentionally unsigned until an upload key is generated and
supplied out-of-band. Generating the keystore is a separate, explicitly-authorized step.

## Intended architecture

- **Google Play App Signing owns the final app-signing key.** Google holds and
  manages the key that end-user devices verify.
- **Developer/CI holds a separate _upload_ key.** We sign uploads with the upload
  key; Play re-signs with the app key on distribution. If the upload key is ever
  lost it can be reset via Play Console without breaking installed users.
- **Distribution channel:** Google Play **internal testing → closed testing** for
  Beta (see `PLAY_STORE_READINESS_CHECKLIST.md` §8). Play Console prefers an
  **AAB** (`bundleRelease`) over an APK for upload. Direct debug-signed APK
  sideloading (how beta3–beta7 were shared) is legacy and should not be used for
  the Play track.

## How signing is wired

`app/build.gradle.kts` reads signing values, in order of precedence:

1. `keystore.properties` at the repo root (gitignored), then
2. `WAVDROP_UPLOAD_*` environment variables (CI encrypted secrets).

The four values resolve into one of three states, evaluated at Gradle
configuration time:

| State | Values supplied | Behavior |
|-------|-----------------|----------|
| **None** | zero of four | Release build stays **unsigned** (safe local default). `assembleRelease` succeeds and emits `app-release-unsigned.apk`. |
| **Complete** | all four | A `release` signing config is created and attached. The keystore file is checked for existence at configuration time (see below). |
| **Partial / misconfigured** | one to three | Gradle **fails fast** with a `GradleException` naming the **missing** property names. No unsigned artifact is silently produced. |

There is **no fallback to the debug key** for release in any state.

The partial-configuration error reports only which property names are missing —
it never echoes supplied paths, passwords, aliases, or other secret values.

### Keystore file validation (complete state)

When all four values are supplied, the configured `storeFile` is resolved and
checked for existence. If it is missing, the build fails with a clear error that
surfaces only the file **name** (not the full, potentially sensitive path).
Passwords are **not** validated at configuration time — an incorrect password
surfaces later, during the actual signing task.

| Property (`keystore.properties`) | Environment variable            |
|----------------------------------|---------------------------------|
| `storeFile`                      | `WAVDROP_UPLOAD_STORE_FILE`     |
| `storePassword`                  | `WAVDROP_UPLOAD_STORE_PASSWORD` |
| `keyAlias`                       | `WAVDROP_UPLOAD_KEY_ALIAS`      |
| `keyPassword`                    | `WAVDROP_UPLOAD_KEY_PASSWORD`   |

Template: [`keystore.properties.example`](../keystore.properties.example) (no real values).

## Secret hygiene

`.gitignore` blocks `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pepk`.
Never place passwords in Gradle files, source, docs, logs, or shell history. Keep
the keystore backed up securely and off Git — losing it (before Play App Signing
enrollment) means no future updates.

## Manual setup still required (not done here — needs explicit authorization)

1. Generate an upload keystore (`keytool -genkeypair … -keyalg RSA -keysize 2048 -validity 10000`)
   and store it securely outside the repo.
2. Copy `keystore.properties.example` → `keystore.properties` and fill in real values.
3. Build the upload artifact: `./gradlew.bat bundleRelease` (AAB) or `assembleRelease` (APK).
4. Create the app in Play Console, **enroll in Play App Signing**, and upload to the
   internal testing track. Play generates/holds the app key; our key is the upload key.
5. For CI: store the keystore + the four `WAVDROP_UPLOAD_*` values as encrypted secrets.

## Verification

```bash
# Unsigned today (expected until credentials are supplied):
apksigner verify --verbose app/build/outputs/apk/release/app-release-unsigned.apk
#   → DOES NOT VERIFY / Missing META-INF/MANIFEST.MF

# Once signed with a real upload key:
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## versionCode / package notes

- `versionCode = 9`, `versionName = "0.1.0-beta9"`. Each Play upload needs a unique,
  monotonically increasing `versionCode` — bump before every new upload.
  (The readiness checklist's "Currently `1`" note is stale; the build is at 9.)
- `applicationId = com.launchpoint.wavdrop` — this is **permanent** once first
  uploaded to Play and can never change for this app listing.
