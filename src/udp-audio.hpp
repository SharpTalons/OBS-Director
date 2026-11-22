/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct audio_data;

/*
 * Sends the program mix (mix 1) to every listening device as raw PCM over UDP.
 *
 * Packet layout, little endian (see docs/PROTOCOL.md):
 *   0  char[4]  "ODA1"
 *   4  u32      sequence number
 *   8  u64      recording file time in microseconds (0 when not recording)
 *  16  u32      sample rate (48000)
 *  20  u16      frames in this packet (240 = 5 ms)
 *  22  u8       channels (2)
 *  23  u8       flags (bit 0: recording, bit 1: recording paused)
 *  24  s16[]    interleaved PCM
 */
class UdpAudio {
public:
	static constexpr uint32_t kSampleRate = 48000;
	static constexpr uint16_t kFramesPerPacket = 240;
	static constexpr uint8_t kChannels = 2;

	UdpAudio();
	~UdpAudio();

	void addDestination(const std::string &ip, uint16_t port);
	void removeDestination(const std::string &ip, uint16_t port);
	void clearDestinations();
	size_t destinationCount();

	/* Recording clock, set from frontend events on the UI thread, read on the audio thread. */
	void recordingStarted();
	void recordingStopped();
	void recordingPaused(bool paused);
	uint64_t fileTimeUs() const;
	bool recording() const { return recActive.load(); }
	bool paused() const { return recPaused.load(); }

private:
	struct Dest {
		std::string ip;
		uint16_t port;
	};

	static void audioCallback(void *param, size_t mixIdx, struct audio_data *data);
	void onAudio(const int16_t *pcm, uint32_t frames);
	void sendPacket(const int16_t *pcm);
	void start();
	void stop();

	std::mutex destMutex;
	std::vector<Dest> dests;
	bool connected = false;

	std::vector<int16_t> pending;
	uint32_t seq = 0;
	intptr_t sock = -1;

	std::atomic<bool> recActive{false};
	std::atomic<bool> recPaused{false};
	std::atomic<uint64_t> recStartNs{0};
	std::atomic<uint64_t> pausedTotalNs{0};
	std::atomic<uint64_t> pauseStartNs{0};
};
