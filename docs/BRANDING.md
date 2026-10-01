# Wavdrop Branding

Concise current branding facts, verified against `AndroidManifest.xml`, `AppIconChoice`, and the
launcher resources.

## Names

- Product name: **Wavdrop Music Player** (`app_name`); launcher label: **Wavdrop** (`launcher_label`).
- Naming history: `Lyra` -> `EchoVault` -> Wavdrop (final; do not rename again).
- Package / application id: `com.launchpoint.wavdrop` (permanent once published).

## Launcher icons

Launcher icon switching uses Android `activity-alias` entries targeting `MainActivity`; the app icon
and round icon on `<application>` are the Obsidian Black variants. Exactly one alias is enabled at a time.

| Icon | Alias | Default |
|---|---|---|
| Obsidian Black | `MainActivityAliasObsidianBlack` | **Yes** (enabled at install; `AppIconChoice.DEFAULT`) |
| Midnight Violet | `MainActivityAliasMidnightViolet` | no |
| Clean Purple | `MainActivityAliasCleanPurple` | no |
| Deep Teal | `MainActivityAliasDeepTeal` | no |
| Ocean Blue | `MainActivityAliasOceanBlue` | no |
| Sunset Orange | `MainActivityAliasSunsetOrange` | no |

Users choose an icon in Settings -> Appearance -> App Icon. The choice is also part of backups
(`launcherIcon` in `preferences.android`). Launcher caching can delay the visible change on some launchers.

Resources: `app/src/main/res/mipmap-nodpi/wavdrop_icon_<variant>.png` and `..._round.png` for all six
variants. Every alias also declares the legacy `MUSIC_PLAYER` filter so Samsung's "Play music" shortcut
resolves Wavdrop (see the comment in the manifest).

Source artwork kept for reference lives in `app/src/main/assets/branding/` (Midnight Violet, Clean Purple,
and Deep Teal PNGs). It is not the shipped launcher resource set.

## Brand notes

- The mark is a wave-and-play symbol; all variants share it.
- Midnight Violet was the original primary identity; **Obsidian Black has been the default since
  Beta 3**. Do not describe Midnight Violet as the default.
- For the Play Store icon asset use the Obsidian Black variant (see `PLAY_STORE_READINESS_CHECKLIST.md`).
