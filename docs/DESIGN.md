# OBS Director design constants

These are the fixed values the Android app, the browser version and the OBS dock share. In code they live in:

- **Android app:** `android/app/src/main/java/dev/sharptalons/obsdirector/ui/Theme.kt` (`T`, `Tints`, `MeterColors`, `Stage`, `Type`, `FeedbackTokens`)
- **Browser version:** the CSS variables at the top of `src/web/app.css`, and `feedback` in `src/web/app.js`
- **OBS dock:** `src/desk-dock.cpp` (the chat's own colours, the same in every OBS theme)

When one changes, change it in each place and here.

## Colour

The app is dark only. It's used in dim rooms beside a stage.

| Token | Value | Use |
| --- | --- | --- |
| ground | `#121214` | Screen background |
| surface | `#1C1C20` | Cards and sections |
| surface-2 | `#1E1E22` | Round icon buttons |
| sunken | `#151518` | Fields, export options |
| raised / control fill | `#2A2A30` | Secondary fills, the other side's chat bubbles |
| control / line-strong | `#3A3A40` | Strong lines, a switch that is off |
| rule | `#26262B` | Dividers |
| text | `#FFFFFF` | Primary text |
| text-2 | `#C9CBD0` | Secondary text |
| muted | `#A6A8AE` | Captions; at least 4.5:1 on every surface |
| blue | `#2B66B0` | Filled buttons (white text 5.8:1), a switch that is on |
| blue-ink / accent | `#7FB0EC` | Blue text, links, focus rings, seen ticks |
| positive | `#4CC38A` | Connected, good |
| negative / rec | `#FF5C76` | Recording, Clip, destructive |
| warning | `#F2C46B` | Warnings |
| on-badge | `#0B1A12` | Dark text on a green, amber or red badge |

**Tints** come in pairs: a dark `bg` with a light `ink` for text and icons on it.

| Tint | bg | ink | Means |
| --- | --- | --- | --- |
| rec | `#3A1B22` | `#FF8A9C` | Recording, clips, live, severe alerts |
| ok | `#183327` | `#6EDDA8` | Good status, connection, the desk's name |
| blue | `#172A40` | `#7FB0EC` | Play, info, bitrate |
| teal | `#14303A` | `#5FD4E8` | Listening, adjust |
| amber | `#33291A` | `#F2C46B` | Warnings, notes, controls, alerts |
| orange | `#3A2614` | `#F7A35C` | Alert settings |
| purple | `#2A2340` | `#B9A2FF` | Disk, audio on the phone, share |

**Desk chat**, like a messaging app:

| Token | Value |
| --- | --- |
| Your own bubbles | `#005C4B`, with their time and ticks in `#A5D6C9` |
| Other people's bubbles | `#2A2A30` |
| Sender names | Each sender keeps one colour from the tint inks |

**Meters** use OBS's own colours:

| Zone | Lit | Unlit |
| --- | --- | --- |
| Green, to −20 dB | `#4CFF4C` | `#1F4A1F` |
| Yellow, to −9 dB | `#FFFF4C` | `#4A4A1F` |
| Red, above −9 dB | `#FF4C4C` | `#4A1F1F` |

- **Scale:** −60 to 0 dB.
- **Fall:** 23.53 dB a second.
- **Peak hold:** 20 s.

**Stage mode** is black (`#000000`), with dimmed versions of the colours above. Time `#E2E3E6`, text `#D4D5D8`, clip `#5A1E28`.

## Type

- **Font:** Roboto, in regular, medium and bold, with tabular figures for every time and number.
- **Sizes:**

| Style | Size |
| --- | --- |
| Screen titles | 24 sp |
| Card titles | 16–17 sp |
| Body | 14–15 sp |
| Captions | 12–13 sp |
| Timecode | 46 sp on Monitor, 72 sp in Stage mode |

## Size and shape

- **Touch targets:** at least 48 dp, everywhere. Buttons are 48 dp tall, round icon buttons 48 dp across; switches are 52×30 with a 48 dp touch area.
- **Navigation:** tabs along the bottom on phones; a 88 dp rail down the side when the window is 600 dp wide or more. Content keeps a 640 dp reading width.
- **Unread badge on Desk:** green with the count; amber when an alert is unread, red for a severe one. The same in the app and the browser version. Connection notes don't count.
- **Two panes:** on wide windows, Recordings shows the list and the open recording side by side.
- **Confirmations:** a snackbar at the bottom, above the tabs (Android's own in the app; `role="status"` in the browser version).
- **OBS dock text** follows the PC's text size: the body is OBS's own font size, names and quick replies 92% of it, notes 85%, times 78%.
- **Quick replies** in the dock wait 3 s before sending; clicking again cancels.
- **Rounding:**

| Element | Radius |
| --- | --- |
| Cards | 18–20 dp |
| Buttons, the message box and the chat send button | Fully round |
| Tiles | 10–14 dp |
| Chat bubbles | 14 dp, with 4 dp on the corner nearest the sender |

- **Spacing:** 16 dp page margins, 12 dp between cards, 8–10 dp inside groups.
- **Fixed slots:** controls that come and go (the status line under the Clip button, chips, captions) have a fixed place and height, so nothing else moves.

## Touch sound

- **The sound:** one tap, cut from the supplied recording of a touchscreen tap.
  - **The recording:** 2.59 s, 48 kHz mono, containing ten taps at 0.04, 0.30, 0.60, 0.87, 1.20, 1.44, 1.71, 1.98, 2.18 and 2.44 s.
  - **The tap used:** the one at **0.60 s**. It is strong and clean, and fades out within 31 ms. The one at 1.20 s rings for 250 ms, and the ones at 0.30 and 1.98 s are faint.
  - **Cut:** 0.595 s to 0.655 s, so **60 ms**, with a 15 ms fade-out. Peak normalised to −3 dBFS. 16-bit mono WAV at 48 kHz, 5.8 KB.
  - **Files:** `android/app/src/main/res/raw/touch_tap.wav`, and the same file as `src/web/tap.wav`, served by the plugin at `/tap.wav`.
- **When it plays:** on the release of a press on any button, tile, row, switch or tab, and when a hold completes.
  - **App:** through the app's own tap indication, set once in `DirectorTheme`.
  - **Browser version:** on any button, switch, tab, link or menu.
- **Volume:** Settings, Controls, **Touch sound**: Off, 25, 50 (default), 75 or 100%.
  - 100% plays at gain **0.6** (`TAP_MAX_GAIN`), so a tap sits under the mix you're listening to, not over it.
  - Up to 3 taps can overlap (`TAP_STREAMS`).
- **Silent phone:** the app plays no tap sound while the phone is on silent or vibrate, as Android's own touch sounds don't.
- **OBS dock:** never makes a sound, since OBS may be capturing the PC's audio. A new message while OBS is in the background flashes OBS in the taskbar instead.

## Vibration

Settings, Controls, **Vibration** sets the strength: Off, Light, Medium (default) or Strong. Choosing one plays a preview.

| | Light | Medium | Strong |
| --- | --- | --- | --- |
| Tap tick (Android's built-in effects) | `EFFECT_TICK` | `EFFECT_CLICK` | `EFFECT_HEAVY_CLICK` |
| Buzz strength (amplitude, 1–255) | 70 | 150 | 255 |
| Buzz length, on phones that can't vary strength | ×0.5 | ×1 | ×1.4 |
| Browser tap (`navigator.vibrate`) | 8 ms | 14 ms | 22 ms |

The patterns below are off/on pairs in milliseconds, starting with a pause. The browser version scales the "on" parts by the same ×0.5, ×1 or ×1.4.

| Moment | Pattern |
| --- | --- |
| A clip starts | `0, 20` |
| A clip is saved | `0, 20, 60, 20` |
| A message in the desk chat | `0, 40, 70, 40` |
| An alert | `0, 200, 100, 200` |
| A severe alert (recording stopped by someone else, disk nearly full, battery at 5%) | `0, 300, 120, 300, 120, 300` |
| The connection to the PC drops | `0, 200, 100, 200` |
| Preview in Settings | `0, 40, 80, 40` |

## Alerts

- **Where they go:** alerts don't sit on top of the screens. Each one sends a notification and vibrates.
- **In the chat:** each also shows up as a centred card in the Desk chat, amber or red for a severe one.
  - The card keeps its action, **Start recording** or **Tell the desk**.
  - Cards stay on that device; they aren't sent to the group.
  - Tapping an alert notification opens the chat.
- **Connection notes:** losing the connection is an alert card. Reconnecting adds a small centred note.
