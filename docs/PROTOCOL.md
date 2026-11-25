# OBS Director protocol, version 2

How the OBS plugin and the OBS Director app talk on a local network. Everything is little endian.

| Port | Transport | Direction | What |
| --- | --- | --- | --- |
| 4466 | TCP | app → plugin | Control: newline-delimited JSON; each phone is approved once in OBS |
| 4467 | UDP | plugin → network, and both ways for probes | Discovery beacon every 2 s; answers to probes |
| (app's choice) | UDP | plugin → app | The program mix as PCM |
| 4468 | TCP | app → plugin | Live output as H.264, opened with a token |
| 4470 | TCP (HTTP, WebSocket) | browser → plugin | The browser version: the page, then the control channel, the mix and the live output on one WebSocket |

The plugin listens on IPv4 only. Its Windows installer allows TCP 4466, 4468 and 4470 and UDP 4467 through Windows Firewall, from the local subnet only.

Version 2 replaced the PIN with approve-once pairing. An app or plugin on version 1 can't pair with one on version 2: install both from the same release.

## Discovery

The plugin broadcasts a JSON datagram to UDP 4467 on every IPv4 interface:

```json
{"obsdirector":2,"id":"0b9e…","name":"STREAM-PC","address":"192.168.1.24","port":4466,"obs":"33.0.2","plugin":"0.4.0"}
```

- `id` is the PC's own random ID, kept in the plugin's config. A PC with two network adapters beacons from both; the app lists it once.
- `address` is the address Tools, OBS Director shows: the one phones last reached this PC on, or before any phone has, the PC's main network address. The app shows this address whenever it can hear the PC on it, so the app and OBS agree; otherwise it shows the address the beacon came from.

A phone can also ask. It sends `{"obsdirector":"probe"}` to UDP 4467 at one PC's address, or to 255.255.255.255 for all of them, from its own UDP 4467, and each plugin answers at once with its beacon, sent straight back. That names a PC typed in by address and lets Search answer without waiting for the next beacon. The plugin answers private (local network) addresses only, at most 20 times a second.

## Control channel (TCP 4466)

One JSON object per line, UTF-8. The app speaks first and must say hello within 10 seconds.

### Pairing: approve once in OBS

```json
→ {"type":"hello","deviceId":"5f0c2d6e-…","device":"Stage phone","proto":2}
← {"type":"welcome","proto":2,"pc":"STREAM-PC","obs":"33.0.2","plugin":"0.4.0","audioRate":48000,"videoPort":4468}
```

`deviceId` is a random ID the app makes once and keeps (16 to 64 letters, digits and dashes). If OBS already knows it, the plugin welcomes the phone at once.

If it doesn't, the plugin asks in OBS: a non-modal "Allow "Stage phone" to connect?" with **Allow** and **Deny**. Meanwhile the phone gets:

```json
← {"type":"pending","message":"Waiting for someone to press Allow in OBS on STREAM-PC."}
```

- **Allow:** the plugin remembers the ID (up to 50 phones; Tools, OBS Director lists them and can remove them) and sends `welcome`.
- **Deny:** `{"type":"error","code":"denied","message":"…"}` and the connection is closed. That address must wait 60 s before it can ask again (`locked_out`).
- **No answer within 2 minutes:** `{"type":"error","code":"approval_timeout",…}`; that address waits 30 s.
- If the phone gives up first, the question in OBS goes away and doesn't count as Deny.

Other refusals: `update_app` (a hello without a valid `deviceId`, from a version 1 app), `busy` (this phone is already waiting, or three phones already are), `locked_out`. At most eight connections that haven't been welcomed are held at once; more are refused. Removing a phone in OBS disconnects it at once.

The status carries the recording's file name only, never its full path.

### Status (plugin → app, every 250 ms)

```json
{"type":"status","t":1795365974000,
 "rec":{"active":true,"paused":false,"fileTimeMs":2331000,"file":"2026-11-22 20-35-39.mp4","container":"mp4","bytes":11660000000,"diskFreeMB":421888},
 "stream":{"active":true,"durationMs":6128000,"kbps":5940.2,"dropped":214,"total":367680,"congestion":0.0},
 "pc":{"cpu":18.4,"fps":60.0,"renderLag":0,"encodeLag":0},
 "scene":"Speaker wide"}
```

`fileTimeMs` is the position in the recording file: time since the recording started, minus time spent paused.

### Commands (app → plugin)

```json
→ {"type":"cmd","id":7,"cmd":"record.stop","args":{}}
← {"type":"result","id":7,"ok":true}
```

| Command | Args | Does |
| --- | --- | --- |
| `record.start` | | Starts recording |
| `record.stop` | | Stops recording; the plugin remembers who asked |
| `record.pause` / `record.resume` | | Pauses or resumes |
| `stream.stop` | | Ends the stream (the app hides this unless allowed in its settings) |
| `audio.start` | `port` | Starts sending the mix to this device's IP on `port`; result has `rate`, `channels`, `frames` |
| `audio.stop` | | Stops the mix to this device |
| `video.start` | | Result has `token`, `port`, `width`, `height` for the video channel |
| `video.stop` | | Closes this device's video channel |
| `chapter.add` | `name` | Adds a chapter marker at the current position (OBS 30.2+, Hybrid MP4 only); `ok:false, error:"chapters_unsupported"` otherwise |
| `clips.write` | `file`, `edl`, `csv` | Saves the clip list next to the recording as `<name>.edl` and `<name>.csv`. `file` is the recording's file name only, and must be a recording OBS made while the plugin was running (`error:"unknown_recording"` otherwise). At most 1 MB each. Result has `files` (the names written) and `pc`. Added in 0.5.0; older plugins answer `unknown_command` |
| `desk.send` | `id`, `text` | Posts a message in the desk chat (up to 1000 characters, line breaks kept). `id` is the app's own message ID (letters, digits, dashes, up to 64); sending the same `id` again doesn't post twice. Result has `id` and `at` |
| `desk.history` | | Result has `messages`: the chat so far in this OBS session (up to 500), each as in `desk.message` |
| `desk.feed` | `text` | Adds a line to the dock's activity feed |

### Desk chat

Since 0.7.0 the desk is a group chat. The OBS dock, every phone and every browser see every message. The plugin keeps the conversation for the OBS session and tells each device which messages are its own (`mine`), without ever sending out a device's ID. The desk writes from the dock, and its messages have `desk: true`. Quick replies in the dock ("Got it", "On it", "Fixed") are ordinary messages.

A ping keeps the line warm: `{"type":"ping","t":…}` gets `{"type":"pong","t":…}`.

### Events (plugin → app)

```json
{"type":"event","event":"record.stopped","data":{"by":"","file":"2026-11-22 20-35-39.mp4"}}
```

| Event | Data |
| --- | --- |
| `record.started` | `by`: the device that asked, or empty if started in OBS |
| `record.stopped` | `by`, `file` |
| `record.paused`, `record.resumed` | |
| `stream.started`, `stream.stopped` | |
| `scene.changed` | `scene` |
| `desk.message` | A new chat message, to everyone connected: `id`, `from` (the sender's name, or "Desk"), `text`, `at` (ms), `desk` (written in OBS), `mine` (sent by this device), `seen` (read at the desk) |
| `desk.seen` | `ids`: messages the desk has now read (the dock was showing with OBS in front). The sender's ticks turn blue |

The app raises "Recording stopped on the OBS PC" when `by` is not this device.

## Audio (UDP, plugin → app)

The program mix (mix 1), converted to 48 kHz stereo 16-bit, in 5 ms packets of 240 frames:

| Offset | Type | Field |
| --- | --- | --- |
| 0 | char[4] | `ODA1` |
| 4 | u32 | sequence number |
| 8 | u64 | recording file time of the first sample, µs (0 when not recording) |
| 16 | u32 | sample rate (48000) |
| 20 | u16 | frames in this packet (240) |
| 22 | u8 | channels (2) |
| 23 | u8 | flags: bit 0 recording, bit 1 paused |
| 24 | s16[] | interleaved PCM |

984 bytes per packet, 200 packets a second, about 1.6 Mb/s. Because every packet carries its file time, the app knows exactly which moment of the recording is coming out of its speaker, which is what clips are timed against. A gap in sequence numbers is a lost packet; the app fills it with silence so the saved session audio stays lined up with the recording.

## Live output (TCP 4468)

The app sends the token from `video.start` and a newline. The plugin then sends frames:

| Offset | Type | Field |
| --- | --- | --- |
| 0 | char[4] | `ODV1` |
| 4 | u32 | payload length |
| 8 | u8 | flags: bit 0 codec config (SPS/PPS), bit 1 keyframe |
| 9 | i64 | presentation time, µs |
| 17 | bytes | H.264 Annex B |

The stream is 640×360, x264 baseline with no B-frames, 1.2 Mb/s, one keyframe a second, at about 24 fps (the canvas frame rate divided down). The encoder runs only while at least one device has the video channel open. A slow device has non-key frames dropped rather than building up delay.

## The browser version (TCP 4470)

Added in 0.6.0. The plugin serves a small web app from its own resources (`src/web/`) and speaks the same protocol to it over a WebSocket, so a browser on any computer on the network can do what the phone app does.

### HTTP

| Path | What |
| --- | --- |
| `/` | The page |
| `/app.js`, `/app.css`, `/icon.svg` | Its script, styles and icon |
| `/ws` | The WebSocket (below) |

- **Methods:** only GET; anything else gets 405.
- **Request size:** at most 8 KB, which must arrive within 10 s.
- **Host check:** a request is answered only if its `Host` is an IP address, `localhost`, or the PC's own name (also with `.local`, `.lan` or `.home`); anything else gets 421. That stops a website elsewhere from reaching the plugin by pointing its own name at the PC (DNS rebinding).
- **Headers:** every response says `Cache-Control: no-cache`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Referrer-Policy: no-referrer`.
- **Content Security Policy:** the page has a strict one, so it can load nothing from anywhere else and can only connect back to the same address.

### WebSocket (`GET /ws`)

A standard WebSocket (RFC 6455, version 13). If the request carries an `Origin`, it must be `http://` plus the same `Host`; otherwise 403. So only the plugin's own page can open it, not a page from another site in the same browser.

- **Text frames** carry the same JSON messages as TCP 4466, one per frame, with no newline. The same rules apply:
  - the browser says `hello` within 10 s, with its own random `deviceId`
  - OBS asks **Allow** once
  - the browser then gets `welcome`, `status`, `result`, `event` and `pong`
- **Frame size:** the browser's frames must be masked, unfragmented and at most 64 KB. Anything else closes the connection.
- **Ping and close:** `ping` frames get `pong`, and `close` is answered.

Two commands work differently for a browser:

| Command | Args | Does |
| --- | --- | --- |
| `audio.start` | `ws: true` | Sends the mix over this WebSocket instead of UDP. Each binary frame is one 5 ms audio packet, exactly as in the UDP layout below. A browser that falls 256 KB behind skips packets rather than drifting. |
| `video.start` | `ws: true` | Sends the live output over this WebSocket instead of TCP 4468. Each binary frame is one `ODV1` frame as on TCP 4468, starting from the next keyframe (codec config always goes through). A browser 1 MB behind skips to the next keyframe. |

Binary frames are told apart by their first four bytes, `ODA1` (audio) or `ODV1` (video).

The page plays the mix with the Web Audio API, about 120 ms behind. For the live picture, it repackages each H.264 frame as fragmented MP4 for Media Source Extensions, which work on a plain `http://` page. Settings, clips and the saved session audio (Opus, in 30 s pieces in IndexedDB) stay in that browser.
