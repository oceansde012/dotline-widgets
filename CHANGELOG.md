# Changelog

Curated by hand, no commit log. The release workflow adds the section `## 1.0.<code>` of the build
to the release body; when there is none it uses `## latest`.

## latest
- Tap on the alarm (Head) and the clock (Time) now opens the Clock app directly on Xiaomi and other phones whose clock is `com.android.deskclock`; the diagnostics report lists where each tap goes (TAP TARGETS).
- Buds: a new drawn pair of earbuds (white tip, clear stem) is shown when the earbuds' own picture is not available, and the widget picker preview now looks like the real widget (picture, name, battery, case).
- Languages: English, Ukrainian and Russian. The app follows the phone's language (anything else shows English); under SETTINGS → LANGUAGE you can pick one yourself, and widgets, weather city search and the update notification follow it.
- App screen redesigned: widgets first, setup collapses to one `ALL SET` row, one font family, compact add buttons, even spacing, diagnostics at the bottom; the update card always shows a status.
- Weather: on the small 2x2 widget a long city such as Kamianets-Podilskyi breaks after the hyphen instead of in the middle of a word.
- Power: while the phone charges, the last lit dot of the bar is red and slowly breathes (screen on only).
- Weather: the city is shown on the small 2x2 widget too, to the right of the icon (long names wrap after a hyphen).
- Player: bolder controls, drawn as solid dot shapes (play, pause, previous, next) instead of thin outlines.
- Buds: separate left / right / case battery for Nothing earbuds, asked directly from the earbuds over the same Bluetooth service the Nothing X app uses (read-only battery request; the case is reported while the earbuds are in it).
- Head: the middle shows this month as day dots (past days grey, today red).
- Buds: Nothing X earbuds are drawn white by default with a small gap between them; Android does not tell apps the colour, so the app has a COLOUR button to switch to black.
- Player: when several players are paused, the widget shows the one you played last. Buds: long earbud names wrap to two lines instead of overlapping the battery.
- Buds: the earbud picture now comes from the Nothing X app on your phone for Nothing Ear, Ear (1), Ear (2), Ear (stick) and Ear (a); the widget shows the device's real name instead of the name you gave it; car kits are no longer treated as earbuds. Separate left / right / case battery is only available to system apps, so the widget shows the overall level.
- Widget labels are now drawn by the app in IBM Plex Mono, so they look right on Samsung One UI too, where the launcher used to replace the font.
- Head shows the next alarm on the right, or "no alarm" when there is none.
- Weather shows no number at all until a city is chosen. Buds and Weather texts no longer get cut off; Ukrainian texts use one informal tone.
- Player: a paused session stays visible with its cover; Spotify cover art loads more reliably, and Spotify ads are shown without a fake progress bar.
- Buds: only real headphones count (a connected watch or car is no longer shown as earbuds); battery older than 10 minutes is not shown as current.
- Fixed: the app crashed on open on Android 14 and newer. If something ever goes wrong again, the next start shows what happened with a Copy button.
- True dot-matrix numerals: the clock, temperature, battery level and the date are drawn by the app as round dots, so they look the same on every phone. The clock still ticks every minute.
- New label font with Cyrillic support (IBM Plex Mono), so Ukrainian labels no longer look mismatched.
- Fixed: the battery widget could show an old value; it now reads the live battery every time. Widgets no longer show sample values before they have real data.
- Redesigned Head, Weather, Power and Player (compact idle face) with better contrast and no empty areas.
- Widgets now say what is missing (notification access, Bluetooth, city) instead of showing a misleading state.
- Every widget tap has a fallback and opens something useful. New live diagnostics card in the app with a copyable report.
- Earbuds widget shows a picture of the connected earbuds (from the Nothing X app or Android when available, or your own choice) and the player falls back to notification art for Spotify.
- First public release: six native home screen widgets (Head, Time, Weather, Player, Power, Buds).
- In-app update check: new versions are offered inside the app, Android asks you to confirm every install.
