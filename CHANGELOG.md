# Changelog

Curated by hand, no commit log. The release workflow adds the section `## 1.0.<code>` of the build
to the release body; when there is none it uses `## latest`.

## latest
- True dot-matrix numerals: the clock, temperature, battery level and the date are drawn by the app as round dots, so they look the same on every phone. The clock still ticks every minute.
- New label font with Cyrillic support (IBM Plex Mono), so Ukrainian labels no longer look mismatched.
- Fixed: the battery widget could show an old value; it now reads the live battery every time. Widgets no longer show sample values before they have real data.
- Redesigned Head, Weather, Power and Player (compact idle face) with better contrast and no empty areas.
- Widgets now say what is missing (notification access, Bluetooth, city) instead of showing a misleading state.
- Every widget tap has a fallback and opens something useful. New live diagnostics card in the app with a copyable report.
- Earbuds widget shows a picture of the connected earbuds (from the Nothing X app or Android when available, or your own choice) and the player falls back to notification art for Spotify.
- First public release: six native home screen widgets (Head, Time, Weather, Player, Power, Buds).
- In-app update check: new versions are offered inside the app, Android asks you to confirm every install.
