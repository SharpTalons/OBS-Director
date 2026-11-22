# OBS Director protocol, version 1

How the OBS plugin and the OBS Director app talk on a local network. Everything is little endian.

| Port | Transport | Direction | What |
| --- | --- | --- | --- |
| 4466 | TCP | app → plugin | Control: newline-delimited JSON, paired with a PIN |
| 4467 | UDP broadcast | plugin → network | Discovery beacon, every 2 s |
| (app's choice) | UDP | plugin → app | The program mix as PCM |
| 4468 | TCP | app → plugin | Live output as H.264, opened with a token |

The plugin listens on IPv4 only. Allow OBS through the PC's firewall on a private network the first time Windows asks.

## Discovery

The plugin broadcasts a JSON datagram to UDP 4467 on every IPv4 interface:

```json
{"obsdirector":1,"name":"STREAM-PC","port":4466,"obs":"31.1.1","plugin":"0.1.0"}
```

The app lists every PC heard in the last 10 seconds. A PC can also be added by address; Tools, OBS Director in OBS shows it.

## Control channel (TCP 4466)

One JSON object per line, UTF-8. The app speaks first and must pair within 10 seconds.

### Pairing

```json
→ {"type":"hello","pin":"4826","device":"Stage phone","proto":1}
← {"type":"welcome","proto":1,"pc":"STREAM-PC","obs":"31.1.1","plugin":"0.1.0","audioRate":48000,"videoPort":4468}
```

A wrong PIN gets `{"type":"error","code":"bad_pin","message":"…"}` and the connection is closed. After more than five wrong PINs in a row the plugin makes a new PIN. The PIN is shown, and can be changed, under Tools, OBS Director.

To stop the PIN being guessed, each address gets three free tries; after that it must wait 2 s, then 4 s, 8 s and so on, up to 5 minutes, between tries. While it waits, a `hello` gets `{"type":"error","code":"locked_out","message":"…"}` and is closed. A correct PIN clears the wait. At most eight unpaired connections are held at once; more are refused.

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
| `desk.send` | `id`, `text` | Shows a message in the volunteers' dock |
| `desk.feed` | `text` | Adds a line to the dock's activity feed |

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
| `desk.reply` | `id` of the message answered, `text` ("Got it", "On it", "Fixed") |

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
