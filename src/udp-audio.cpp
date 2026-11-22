/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#include "udp-audio.hpp"

#include <obs.h>
#include <util/platform.h>

#include <algorithm>
#include <cstring>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
using socklen_t = int;
#define CLOSE_SOCKET closesocket
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#define CLOSE_SOCKET close
#endif

namespace {
void put16(uint8_t *p, uint16_t v)
{
	p[0] = uint8_t(v);
	p[1] = uint8_t(v >> 8);
}
void put32(uint8_t *p, uint32_t v)
{
	for (int i = 0; i < 4; i++)
		p[i] = uint8_t(v >> (8 * i));
}
void put64(uint8_t *p, uint64_t v)
{
	for (int i = 0; i < 8; i++)
		p[i] = uint8_t(v >> (8 * i));
}
} // namespace

UdpAudio::UdpAudio()
{
#ifdef _WIN32
	WSADATA wsa;
	WSAStartup(MAKEWORD(2, 2), &wsa);
#endif
	sock = (intptr_t)socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
	pending.reserve(kFramesPerPacket * kChannels * 8);
}

UdpAudio::~UdpAudio()
{
	stop();
	if (sock >= 0)
		CLOSE_SOCKET((decltype(socket(0, 0, 0)))sock);
#ifdef _WIN32
	WSACleanup();
#endif
}

void UdpAudio::start()
{
	if (connected)
		return;
	struct audio_convert_info conv = {};
	conv.samples_per_sec = kSampleRate;
	conv.format = AUDIO_FORMAT_16BIT;
	conv.speakers = SPEAKERS_STEREO;
	conv.allow_clipping = false;
	obs_add_raw_audio_callback(0, &conv, audioCallback, this);
	connected = true;
}

void UdpAudio::stop()
{
	if (!connected)
		return;
	obs_remove_raw_audio_callback(0, audioCallback, this);
	connected = false;
	pending.clear();
}

void UdpAudio::addDestination(const std::string &ip, uint16_t port)
{
	{
		std::lock_guard<std::mutex> lock(destMutex);
		for (auto &d : dests)
			if (d.ip == ip && d.port == port)
				return;
		dests.push_back({ip, port});
	}
	start();
}

void UdpAudio::removeDestination(const std::string &ip, uint16_t port)
{
	bool empty;
	{
		std::lock_guard<std::mutex> lock(destMutex);
		dests.erase(std::remove_if(dests.begin(), dests.end(),
					   [&](const Dest &d) { return d.ip == ip && d.port == port; }),
			    dests.end());
		empty = dests.empty();
	}
	if (empty)
		stop();
}

void UdpAudio::clearDestinations()
{
	{
		std::lock_guard<std::mutex> lock(destMutex);
		dests.clear();
	}
	stop();
}

size_t UdpAudio::destinationCount()
{
	std::lock_guard<std::mutex> lock(destMutex);
	return dests.size();
}

void UdpAudio::recordingStarted()
{
	pausedTotalNs = 0;
	pauseStartNs = 0;
	recPaused = false;
	recStartNs = os_gettime_ns();
	recActive = true;
}

void UdpAudio::recordingStopped()
{
	recActive = false;
	recPaused = false;
}

void UdpAudio::recordingPaused(bool p)
{
	uint64_t now = os_gettime_ns();
	if (p && !recPaused) {
		pauseStartNs = now;
		recPaused = true;
	} else if (!p && recPaused) {
		pausedTotalNs = pausedTotalNs + (now - pauseStartNs);
		recPaused = false;
	}
}

uint64_t UdpAudio::fileTimeUs() const
{
	if (!recActive)
		return 0;
	uint64_t now = recPaused ? pauseStartNs.load() : os_gettime_ns();
	uint64_t elapsed = now - recStartNs - pausedTotalNs;
	return elapsed / 1000;
}

void UdpAudio::audioCallback(void *param, size_t, struct audio_data *data)
{
	auto *self = static_cast<UdpAudio *>(param);
	if (!data || !data->data[0] || !data->frames)
		return;
	self->onAudio(reinterpret_cast<const int16_t *>(data->data[0]), data->frames);
}

void UdpAudio::onAudio(const int16_t *pcm, uint32_t frames)
{
	pending.insert(pending.end(), pcm, pcm + size_t(frames) * kChannels);
	const size_t chunk = size_t(kFramesPerPacket) * kChannels;
	size_t offset = 0;
	while (pending.size() - offset >= chunk) {
		sendPacket(pending.data() + offset);
		offset += chunk;
	}
	pending.erase(pending.begin(), pending.begin() + offset);
}

void UdpAudio::sendPacket(const int16_t *pcm)
{
	uint8_t buf[24 + kFramesPerPacket * kChannels * 2];
	memcpy(buf, "ODA1", 4);
	put32(buf + 4, seq++);
	put64(buf + 8, fileTimeUs());
	put32(buf + 16, kSampleRate);
	put16(buf + 20, kFramesPerPacket);
	buf[22] = kChannels;
	buf[23] = uint8_t((recActive ? 1 : 0) | (recPaused ? 2 : 0));
	uint8_t *out = buf + 24;
	for (size_t i = 0; i < size_t(kFramesPerPacket) * kChannels; i++)
		put16(out + i * 2, uint16_t(pcm[i]));

	std::vector<Dest> copy;
	{
		std::lock_guard<std::mutex> lock(destMutex);
		copy = dests;
	}
	for (auto &d : copy) {
		sockaddr_in addr = {};
		addr.sin_family = AF_INET;
		addr.sin_port = htons(d.port);
		if (inet_pton(AF_INET, d.ip.c_str(), &addr.sin_addr) != 1)
			continue;
		sendto((decltype(socket(0, 0, 0)))sock, reinterpret_cast<const char *>(buf), (int)sizeof(buf), 0,
		       reinterpret_cast<sockaddr *>(&addr), (socklen_t)sizeof(addr));
	}
}
