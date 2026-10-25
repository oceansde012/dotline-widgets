# Dotline Widgets — design spec

Target: a modern Android phone (reference: One UI home, 5-column grid, ~411dp wide, black wallpaper).
Goal: six native widgets (Head, Time, Weather, Player, Power, Buds) that are calm, legible, consistent and never show stale state.

## 1. Design tokens (single source: `Nd.kt` + `res/values`)

| Token | Value | Use |
|---|---|---|
| `bg_tile` | `#101012` | every widget tile (one surface, no translucency) |
| `stroke_tile` | `#34343A`, 1.5dp | tile outline + a 1dp top highlight (`#FFFFFF` 6%) so tiles read as objects |
| `fg` | `#F2F2F2` | primary text, lit dots, glyphs |
| `dim` | `#8E8E93` | secondary text |
| `faint` | `#4A4A4E` | labels on idle, unlit text |
| `dot_off` | `#2C2C2F` | unlit dots of every dot bar/grid |
| `accent` | `#D71921` | **max one small element per widget** (see §3) |
| `chip` | `#232326` | inner chips: cover placeholder, play circle on idle |

Radius: tile radius `R = min(26dp, 0.22 × tile height)` — identical formula for every widget so a 1-row and 2-row widget share the same corner rhythm. Inner elements use `R − padding` (cover art, chips).
Padding: `P = 16dp` on all sides for every widget (scaled `min(16dp, 0.14 × H)` for small heights).
Tile fills the whole cell area minus the launcher padding; no internal margins (removes the misaligned-left-edges bug).

Typography (3 roles only):
- **Hero** — `DotText`: our own 5x7 dot glyph set drawn on a canvas (no font): clock, temperature, battery %, earbud %, day number. Lit dots `#F2F2F2`, unlit matrix `#2C2C2F` at 60%, dot = 0.62 of the pitch.
- **Label** — `label_regular` / `label_medium` (IBM Plex Mono, OFL, Latin + Cyrillic), ALL CAPS, letter-spacing 0.08em, 11–13sp: dates, conditions, status lines, units.
  Labels in widgets are drawn as bitmaps in our own process (`LabelText`, Typeface from app resources) because a launcher may replace the font of a hosted TextView; the app screen uses real TextViews. Tone of voice: informal "ти" (Дозволь, Вибери), short.

- **Title** — system `sans-serif-medium`: track title only (needs full Unicode/Cyrillic); artist in `sans-serif` dim.

## 2. Widgets

### 2.1 Head — 5×1 (`HeadWidgetProvider`)
- Left: `FRI 23 OCT` (Label, fg). Week day and month localised (uk/en from system locale).
- Right: next alarm via `AlarmManager.getNextAlarmClock()`: caption `ALARM` (Label, dim) over the time in dots. If none: an honest dim `NO ALARM` label. No weather here (weather lives only in the Weather widget — kills the duplication).
- Accent: none.
- Tap left → Calendar app; tap right → Clock alarms.
- Refresh: on `ACTION_DATE_CHANGED`, `TIME_SET`, `TIMEZONE_CHANGED`, `NEXT_ALARM_CLOCK_CHANGED` (runtime-registered in `MediaListenerService` process + `onUpdate`), plus a `TextClock` for the date so it rolls over without any update.

### 2.2 Time — 3×2 (`ClockWidgetProvider`, restyle)
- Two `TextClock`s `HH` and `mm`, **both fg**, hero font, vertically centred, sized `min(0.46 H, (W − 2P) / 2.35)`.
- Accent: a single red dot between/above (top-right corner at P). Static.
- Below the digits: empty space.
- Tap → Clock app (keep existing fallback chain).

### 2.3 Weather — 2×2 (`WeatherWidgetProvider`, new)
- Tile is a **square tile, not a red circle**.
- Top-left: dot weather glyph (dot icons stored as coordinate arrays in `WeatherArt` → Canvas bitmap ≤ 96×96px, same pipeline as `PlayerArt`).
- Centre-left: temperature hero `10°`.
- Bottom: Label `CLOUDY` (fg) + second line `H 12° · L 6°` (dim). City name in dim if widget ≥ 3 cells wide.
- Accent: red dot top-right **only** when precipitation probability ≥ 60% in the next 2 hours (shows `RAIN 14:00` instead of H/L).
- Stale: if data older than 3 h, Label turns `faint` and shows `UPDATED 09:12`.
- Tap → opens app's Weather sheet (MainActivity section) with hourly strip; long-press widget handled by launcher.
- Data: Open-Meteo `forecast?current=temperature_2m,weather_code,is_day&hourly=precipitation_probability&daily=temperature_2m_max,temperature_2m_min&timezone=auto`. City chosen in app (geocoding API search) or "Use my location" (one-shot coarse, foreground only). Store lat/lon/name in prefs.
- Refresh policy (Open-Meteo `current` itself updates every 15 min, so faster polling is pointless):
  - `JobScheduler` periodic job, 15 min, `NETWORK_TYPE_ANY`.
  - On `ACTION_USER_PRESENT` (runtime receiver in the listener service process): refresh if data older than 10 min.
  - On widget add / app open: immediate refresh.
  - Re-render (no network) every minute is NOT needed; only the stale label depends on time, computed at render.

### 2.4 Player — 5×2 (`PlayerWidgetProvider`, restyle; keep `MediaTracker` engine)
Keep the existing engine (session picking, 80ms debounce, screen-on tick, partial updates). Changes:
- Layout: cover left (square, radius `R−P`, height `H − 2P`), right column: title (Title, 1 line, ellipsize) + artist (dim) top; **dot progress bar** middle; bottom row: time Label `0:56` left and `−0:59` right, controls right.
- **Dot progress bar**: N = 28 dots in one row (width of the right column), lit = fg, unlit = `dot_off`, the current head dot = **accent** (this is the player's single red element). Rendered as one tiny bitmap (≤ 600×16 px ARGB ≈ 38 KB) per tick, pushed with `partiallyUpdateAppWidget` together with the time text. Ticks every second while playing + screen on (existing tick logic). Bar also covers sub-dot progress by fading the head dot's left neighbour.
- Elapsed time additionally uses a `Chronometer` (`setChronometer(base = elapsedRealtime − pos, running = playing)`) so the seconds keep counting even if a partial update is throttled by the launcher; the remaining time is updated with the dot bar.
- Controls: prev / next = fg dot glyphs, no circle; play/pause = fg circle with **black** glyph (no red). 48dp min touch zones.
- Idle (no session or STOPPED/NONE, or PAUSED > 30 min): cover = chip with dot note, title `Not playing` (dim), artist = last app label, bar hidden, only play circle → resumes last app. Never shows a stale track as playing.
- Paused: same face, play glyph, Chronometer stopped, bar frozen, time Label dim.

### 2.5 Power — 3×1 (`PowerWidgetProvider`, new)
- Left: hero `77` + Label `%`.
- Right: 10-segment dot bar (one dot = 10%), lit fg.
- Status Label under the bar:
  - charging: `CHARGING · FULL IN 1H 05M` from `BatteryManager.computeChargeTimeRemaining()`; if −1 → `CHARGING`.
  - full: `CHARGED`.
  - discharging: `ON BATTERY` (+ temperature `31°C` if > 40 °C, in accent).
- Accent: charging = the last lit dot is red and breathes (separate overlay ImageView over an unlit bar dot; a tick every 200 ms sends only `setImageAlpha`, 20–100% on a 2.6 s cosine; only while the screen is on, stops on unplug / ≤ 15% / screen off / Battery Saver / animations removed, and then the dot is solid red); when ≤ 15% the hero is red. Never both.
- Updates: `ACTION_BATTERY_CHANGED` runtime receiver in the listener-service process (it is long-lived), throttled to one render per 1% change or status change; plus `ACTION_POWER_CONNECTED/DISCONNECTED`.

### 2.6 Buds — 2×1 (`BudsWidgetProvider`, new)
- Connected: dot earbuds glyph left; right: `L 80 · R 75` (Label) and `CASE 40` (dim) if available, else single hero `80%`.
- Disconnected: glyph in `faint`, Label `NOT CONNECTED` (dim). Never "OFF".
- Accent: red dot when any bud ≤ 15%.
- Tap → `Settings.ACTION_BLUETOOTH_SETTINGS`.
- Data, in order: reflection `BluetoothDevice.getMetadata(METADATA_UNTETHERED_{LEFT,RIGHT,CASE}_BATTERY)` → `getBatteryLevel()` → connected-only. Listen to `android.bluetooth.device.action.BATTERY_LEVEL_CHANGED`, `ACL_CONNECTED/DISCONNECTED`, A2DP/HEADSET profile connection state. Needs `BLUETOOTH_CONNECT` runtime permission (asked from app onboarding).

## 3. Accent rule (enforced in review)
Per widget, at most one accent element, and it must *mean* something: Time = the dot; Player = progress head; Power = charging/low; Weather = rain soon; Buds = low battery; Head = none.

## 4. App screen (MainActivity redesign)
Single scroll, black `#000`, same tokens. One type family on the whole screen: Plex Mono for labels, buttons AND body text (the system sans is gone); dot-matrix only for the wordmark.
1. Header: `DOTLINE` in the hero font, version in Label dim.
2. **Widgets gallery** first (it is what the app is for): per widget a title + one-line description, a compact `ADD TO HOME` pill (`requestPinAppWidget`, 40 dp) and the live preview centred under it (the provider's real RemoteViews applied into the activity).
3. **Setup**: one summary row (`✓ ALL SET`, or `2 OF 4 READY` with the red dot). All done = collapsed; anything missing = open. Inside, tile cards with status dot (fg / accent) and one action each: notification access (hint for "Allow restricted settings" on Samsung), Bluetooth, weather city (search field + `USE MY LOCATION`, both quiet chips), auto-updates (install permission). A done card collapses to `✓ READY`. All cards are 12 dp apart.
4. **Settings**: °C/°F, player time remaining/total, the earbuds picture card (two rows of two buttons).
5. **Updates** (release builds): version, then the status line (`✓ UP TO DATE`, never an empty line), then the last check time.
6. **Diagnostics** (live state) at the bottom, collapsed.
7. Footer: `Weather data by Open-Meteo.com` (licence requirement), fonts note.

## 5. Engineering rules
- Plain framework + RemoteViews, no new dependencies (keep the app tiny). Add `INTERNET`, `BLUETOOTH_CONNECT`, `ACCESS_COARSE_LOCATION`.
- One long-lived host for runtime receivers: `MediaListenerService` (already bound by the system once access is granted). Fallback when access is not granted: receivers registered from providers' `onUpdate` + JobScheduler.
- Every bitmap ≤ 300 KB, whole RemoteViews parcel ≤ 500 KB.
- Each provider `previewLayout` so the widget picker shows the real design.
- All sizes derived from real widget size (`WidgetSize.of`) on add/resize.
- Fonts: only OFL fonts are bundled: IBM Plex Mono (labels); licence texts live in `assets/licenses/`.

## 6. Redesign v3 summary
- Numerals are bitmaps from DotText (glyphs: 0-9, A-Z, : . , - + % / ! ? ( ) ' _ = ° ·, a bell; Cyrillic only in label text). Bitmaps are sized to the view, cached by text/size/colour, each <= ~340 KB.
- Clock: HH over mm (or one row, whichever gives larger dots), minute tick from a runtime ACTION_TIME_TICK receiver plus an AlarmManager alarm at the next minute boundary (re-armed on every render).
- Head: day number in dots, weekday over month in the label font, bell + alarm on the right.
- Weather: icon top-left, temperature fills the middle, condition and H/L at the bottom.
- Power: dot 81 + %, 10-dot bar (dot ~8 dp) with the status line on the same top/bottom lines as the numerals.
- Player: playing = title block top, progress group centred, controls bottom (one grid); idle = compact centred row; no notification access = Allow access face.
- Faces never show sample data: initialLayout is an empty tile, previews on Android 15+ are rendered by the same code (setWidgetPreview).
- Diagnostics card in the app (Diagnostics.register for extra sections), taps via Taps with fallbacks.

