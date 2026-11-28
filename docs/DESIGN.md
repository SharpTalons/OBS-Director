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
- **Confirmations:** a snackbar at the bottom, above the tabs (Android's own in the app; `role="status"` in the browser version).
- **OBS dock text** follows the PC's text size: the body is OBS's own font size, names 92% of it, notes 85%, times 78%.
- **OBS dock colours**, for dark screens: background `#0B141A`; others' bubbles `#2B3B45` edged `#3D4E59`; your own `#005C4B` edged `#0A7A63`; message text white (at least 6.5:1); times and notes `#C9D3D8` (at least 5.5:1); sender names from a light set, each at least 6:1 on its bubble.
- **Top bar:** only on Monitor. Recordings, Desk, Settings and Stream start with their own title; while the connection is lost or connecting they show a slim amber line at the top instead. The browser version does the same at phone width.
- **Volume:** 1–100% on a loudness scale (`Loudness` in the app): 1% is −40 dB, 25% −20 dB, 50% the mix as sent, 75% +12 dB, 100% +24 dB. The same in the browser version. The scale shows under the slider, and the line under it says the level in words ("About twice as loud (+12 dB)"); above 50% the slider and that line turn amber, and in the app the line says when the phone's own media volume is below 70%. The boost goes through a limiter: in the app 98% ceiling and 100 ms release, in the browser a Web Audio compressor at −0.5 dB, ratio 20, 1 ms attack and 100 ms release.
- **Rounding:**

| Element | Radius |
| --- | --- |
| Cards | 18–20 dp |
| Buttons, the message box and the chat send button | Fully round |
| Tiles | 10–14 dp |
| Chat bubbles | 14 dp, with 4 dp on the corner nearest the sender |

- **Spacing:** 16 dp page margins, 12 dp between cards, 8–10 dp inside groups.
- **Fixed slots:** controls that come and go (the status line under the Clip button, chips, captions) have a fixed place and height, so nothing else moves.

## Recordings

Two tabs, the same in the app and the browser version: **Clips** (made on the phone or browser) and **OBS recordings** (kept on the PC). They are kept apart on purpose.

- **Clips:** every clip, newest first, under a day heading, in one surface card per day. A clip's name is its OBS recording's name (or the one OBS would give), "clip N", and its note: "2026-11-28 19-02-11 clip 2 – Choir intro", up to two lines. Under it the length and start time, and a small tag: "OBS recording now" (rec), "In the OBS recording" (ok) or "Not recorded in OBS" (neutral).
- **OBS recordings:** a card each, the one OBS is making first with a REC pill. Its time and length, its file name, its clip count, and its AAC: a blue Save as AAC button once it has stopped; while saving, a progress bar on the sunken fill (getting it ready on the PC fills the first fifth, copying the rest) with Cancel; once saved, a Play row. ⋯ opens its clip list export and Share or Download and Delete for the AAC.
- **Monitor:** a compact OBS recording row (pill, 22 sp time, file or next name, 48 round icon buttons: Pause and Stop fill as they're held, Resume and Start act on a tap; Start is a red dot on white). Clipping sits in the Monitoring card under the listening controls, after a rule: the Clip button (its hint is the next clip's name), the status line, and the two latest clips with Play.
- **A clip row** is its Play button (48, round), its name, "length · start time" and its tag, and ⋯. The row turns blue while it plays. A clip from before names goes by its note, or "Clip at 00:52:00".
- **One sheet** for a clip: Play, then three tiles (Trim in teal, Note in amber, Share or Download in purple), then Delete clip. Trim is a trimmer like a phone's video trimmer (rules in `audio/Trim.kt`, and `Trim` in the browser): an 84-high waveform of the clip with 15 s either side, the kept part on the teal tint with its bars in teal ink, and a handle at each end (2 wide line, 12×32 grip with two ridges; white for the end ±1 s moves). Drag an end or the lit part; tap elsewhere to bring the nearer end there. Letting go settles the end in the quietest 100 ms within 0.5 s if it is under 60% of the level where it was let go, and plays 2 s at that edge. Below: Start, the length, End (the selected end underlined in blue), ±1 s, Play, then Reset, Cancel and Save. The minimum clip is 1 s.
- **At the bottom**, floating so the list never moves: Undo after a delete (6 s), then a small player with the clip's (or the AAC's) name, its progress, how much the live mix is lowered, and Stop.

## Floating bubble

- **The app icon**, 56 dp, cut to a circle: the launcher icon's foreground on its own `#121214` background, with a 1 dp `#2A2A30` edge.
- **Clip recording:** a 3 dp red ring round it and a 16 dp red record badge on its top right corner, with a 2 dp ground-coloured edge. Nothing else shows it: OBS recording on its own leaves the icon plain.

## Touch sound

- **The sound:** one tap, cut from the supplied recording of a touchscreen tap.
  - **The recording:** 2.59 s, 48 kHz mono, containing ten taps at 0.04, 0.30, 0.60, 0.87, 1.20, 1.44, 1.71, 1.98, 2.18 and 2.44 s.
  - **The tap used:** the one at **0.60 s**. It is strong and clean, and fades out within 31 ms. The one at 1.20 s rings for 250 ms, and the ones at 0.30 and 1.98 s are faint.
  - **Cut:** 0.595 s to 0.655 s, so **60 ms**, with a 15 ms fade-out.
  - **Made louder (0.8.0):** compressed (tanh, drive 4) so its body is fuller, then brought up to **−0.5 dBFS** peak. Its average level went from −22 to **−13 dBFS**, about 9 dB louder. 16-bit mono WAV at 48 kHz.
  - **Files:** `android/app/src/main/res/raw/touch_tap.wav`, and the same file as `src/web/tap.wav`, served by the plugin at `/tap.wav`.
- **When it plays:** on the release of a press on any button, tile, row, switch or tab, and when a hold completes.
  - **App:** through the app's own tap indication, set once in `DirectorTheme`.
  - **Browser version:** on any button, switch, tab, link or menu.
- **Volume:** Settings, Controls, **Touch sound**, on a loudness scale (`FeedbackTokens.tapGain`):

| Setting | Off | 25% | 50% (default) | 75% | 100% |
| --- | --- | --- | --- | --- | --- |
| Level | silent | −12 dB | −6 dB | −3 dB | 0 dB, full |

- **Which volume:** the app plays it on the **media** volume, the one the volume buttons set while you listen, not the quieter system-sounds volume. Up to 3 taps can overlap (`TAP_STREAMS`).
- **Silent phone:** the app plays no tap sound while the phone is on silent or vibrate, as Android's own touch sounds don't.
- **OBS dock:** never makes a sound, since OBS may be capturing the PC's audio. A new message while OBS is in the background flashes OBS in the taskbar instead.

## Vibration

Settings, Controls, **Vibration** sets the strength: Off, Light, Medium (default) or Strong. Choosing one plays a preview. Android's built-in "click" effects are too faint on many phones, so the app sets each pulse's length and, where the phone can vary it, its strength.

| | Light | Medium | Strong |
| --- | --- | --- | --- |
| Tap (one pulse) | 18 ms at 140 | 28 ms at 210 | 40 ms at 255 |
| Buzz strength (amplitude, 1–255) | 140 | 210 | 255 |
| Buzz length (all phones) | ×0.8 | ×1.2 | ×1.6 |
| Browser tap (`navigator.vibrate`, length only) | 18 ms | 28 ms | 40 ms |

The patterns below are off/on pairs in milliseconds, starting with a pause, before the length scale. The browser version scales the "on" parts the same way.

| Moment | Pattern |
| --- | --- |
| A clip starts | `0, 30` |
| A clip is saved | `0, 30, 70, 30` |
| A message in the desk chat | `0, 60, 80, 60` |
| An alert | `0, 300, 120, 300` |
| A severe alert (recording stopped by someone else, disk nearly full, battery at 5%) | `0, 400, 150, 400, 150, 400` |
| The connection to the PC drops | `0, 300, 120, 300` |
| Preview in Settings | `0, 60, 90, 60` |

## Alerts

- **Where they go:** alerts don't sit on top of the screens. Each one sends a notification and vibrates.
- **In the chat:** each also shows up as a centred card in the Desk chat, amber or red for a severe one.
  - The card keeps its action, **Start recording** or **Tell the desk**.
  - Cards stay on that device; they aren't sent to the group.
  - Tapping an alert notification opens the chat.
- **Connection notes:** losing the connection is an alert card. Reconnecting adds a small centred note.
