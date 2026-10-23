# Dotline Widgets

**Calm dot-matrix home screen widgets for Android. Six native widgets, no account, no ads, no analytics.**

[Українська версія](README.uk.md)

> Inspired by Nothing OS design; not affiliated with Nothing Technology Limited.

## Screenshots

<!-- TODO: add screenshots (home screen with all six widgets, app screen, widget picker) -->

## Widgets

| Widget | Size | What it does |
|---|---|---|
| **Head** | 5x1 | Date and next alarm in one line. Tap the date for the calendar, the alarm for the clock. |
| **Time** | 3x2 | Big dot-matrix clock (native `TextClock`, so it never lags) with a single red dot. |
| **Weather** | 2x2 | Dot-matrix icon, temperature, condition, high / low. A red dot appears when rain is likely soon. Shows how old the data is when it gets stale. |
| **Player** | 5x2 | Cover, title, artist and controls of whatever is playing, with a **per-second dot progress bar** (28 dots, the current dot in red), elapsed and remaining time. Idle state resumes your last player. |
| **Power** | 3x1 | Battery percent, 10-dot level bar, charging status and time to full. |
| **Buds** | 2x1 | Left, right and case battery of connected earbuds, or "not connected". |

Design rules (dark theme only, one red accent per widget at most) are in [DESIGN_SPEC.md](DESIGN_SPEC.md).
Requires Android 12 (API 31) or newer. Tested on One UI.

## Install

1. Open the [latest release](../../releases/latest) and download `dotline-widgets.apk`.
2. Open the file. Android asks to allow installs from this source ("Install unknown apps"); allow it for your browser or file manager.
3. Open **Dotline Widgets** and follow the setup cards:
   - **Notification access** (needed for the Player widget). If the switch is greyed out, you are on a device that restricts sideloaded apps (for example **Samsung One UI**): go to *Settings > Apps > Dotline Widgets > the three-dot menu > **Allow restricted settings***, then try again.
   - Pick a city for the Weather widget (or use your location once).
   - Allow Bluetooth for the Buds widget.
4. Add widgets with **ADD TO HOME** in the app, or long-press the home screen > Widgets > Dotline Widgets.

Updates: the app checks GitHub Releases and offers new versions. Android shows its own confirmation dialog for every install; there are no silent installs.

### Obtainium

You can also track releases with [Obtainium](https://github.com/ImranR98/Obtainium): add the app with the source URL

```
https://github.com/oceansde012/dotline-widgets
```

The built-in updater and Obtainium can coexist; use whichever you prefer.

## Permissions

| Permission | Why |
|---|---|
| Notification access | Reads the active **media sessions** (title, artist, cover, position, play state) for the Player widget, and sends play / pause / next. Notification contents are never read, stored or sent anywhere. |
| Internet | Weather from Open-Meteo, city search, and the update check against GitHub Releases. No other requests. |
| Coarse location (optional) | Only if you tap "Use my location" to pick the weather city. One-shot, foreground only, never stored beyond the city you confirm. |
| Bluetooth (nearby devices) | Reads the battery level of connected earbuds for the Buds widget. |
| Install packages | Self-update: downloads the new APK from the GitHub release and hands it to Android's installer, which asks you to confirm. |
| Post notifications | Tells you that an update has been downloaded and is ready. |
| Run at boot | Re-draws the widgets after a reboot. |

## Privacy

- No analytics, no ads, no crash reporters, no account, no tracking.
- Your chosen city (name and coordinates) is stored **only on your device**.
- The only network requests are:
  - [Open-Meteo](https://open-meteo.com) (weather forecast and city search), containing the city's coordinates or search text;
  - the GitHub releases API of this repository (update check) with a generic `DotlineWidgets/<version>` User-Agent, and the APK download from the release.
- No device identifiers are sent.

## Build from source

Requirements: JDK 17 and the Android SDK (API 35). Android Studio works too (open the repository root).

```
# Windows
.\gradlew.bat assembleDebug
# Linux / macOS
./gradlew assembleDebug
```

- `assembleDebug` produces `io.github.oceansde012.dotline.dev` ("Dotline Dev"), which installs next to the release app and has the updater disabled.
- `assembleRelease` uses the debug key when you build locally. On CI the build refuses to run without the release key.
- Put `sdk.dir=<path to your Android SDK>` in `local.properties` if `ANDROID_HOME` is not set (the file is git-ignored).

### Release signing and CI

The workflow [`.github/workflows/release.yml`](.github/workflows/release.yml) builds and publishes a release on every push to `main` (version code = run number + 100, tag `v<code>` created by the workflow) and on pushed tags `v<number>`. It uses the built-in `GITHUB_TOKEN` (`contents: write`); no personal token is needed. Set four repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | base64 of your `.jks` file, one line |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password (same as the store password is fine) |

```
keytool -genkeypair -v -keystore release.jks -alias dotline -keyalg RSA -keysize 4096 -validity 10000
# base64 on one line (Linux/macOS):  base64 -w0 release.jks
# Windows PowerShell:  [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```

Keep the keystore somewhere safe and never commit it. All releases must be signed with the same key, otherwise updates will not install over each other. The release notes come from a fixed template plus the matching section of [CHANGELOG.md](CHANGELOG.md).

## Credits and licences

- Weather data by [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0).
- Font under the SIL Open Font License 1.1 (texts in `app/src/main/assets/licenses/`):
  - [IBM Plex Mono](https://github.com/IBM/plex) (Regular and Medium) for labels, it covers Latin and Cyrillic.
  - The dot-matrix numerals are drawn by the app itself, no font is involved.
- Code: [MIT](LICENSE) (c) 2026 oceansde012.

This project ships only open-licensed fonts and its own artwork.
