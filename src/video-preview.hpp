/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#pragma once

#include <cstdint>
#include <functional>
#include <vector>

#include <obs.h>

/*
 * A small H.264 copy of the program output for framing checks: 640x360, about 24 fps,
 * x264 baseline with no B-frames, 1.2 Mb/s. It runs only while at least one device
 * has the live output switched on, and is separate from the recording and the stream.
 */
class VideoPreview {
public:
	/* config: SPS/PPS (sent before the first frame and whenever it changes). */
	using PacketFn =
		std::function<void(const uint8_t *data, size_t size, int64_t ptsUs, bool keyframe, bool config)>;

	static void registerOutput();

	explicit VideoPreview(PacketFn fn);
	~VideoPreview();

	bool start();
	void stop();
	bool active() const { return running; }

private:
	static void onPacket(void *param, struct encoder_packet *pkt);

	PacketFn sink;
	obs_output_t *output = nullptr;
	obs_encoder_t *encoder = nullptr;
	bool running = false;
	bool sentConfig = false;
};
