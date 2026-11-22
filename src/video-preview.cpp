/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#include "video-preview.hpp"

#include <obs-module.h>
#include <plugin-support.h>

#include <cmath>

namespace {

struct PreviewOutput {
	obs_output_t *output;
	void (*cb)(void *, struct encoder_packet *);
	void *param;
};

const char *outName(void *)
{
	return obs_module_text("OBSDirector.Preview");
}

void *outCreate(obs_data_t *, obs_output_t *output)
{
	auto *o = new PreviewOutput();
	o->output = output;
	o->cb = nullptr;
	o->param = nullptr;
	return o;
}

void outDestroy(void *data)
{
	delete static_cast<PreviewOutput *>(data);
}

bool outStart(void *data)
{
	auto *o = static_cast<PreviewOutput *>(data);
	if (!obs_output_can_begin_data_capture(o->output, 0))
		return false;
	if (!obs_output_initialize_encoders(o->output, 0))
		return false;
	return obs_output_begin_data_capture(o->output, 0);
}

void outStop(void *data, uint64_t)
{
	auto *o = static_cast<PreviewOutput *>(data);
	obs_output_end_data_capture(o->output);
}

void outPacket(void *data, struct encoder_packet *pkt)
{
	auto *o = static_cast<PreviewOutput *>(data);
	if (o->cb && pkt)
		o->cb(o->param, pkt);
}

} // namespace

void VideoPreview::registerOutput()
{
	struct obs_output_info info = {};
	info.id = "obs_director_preview";
	info.flags = OBS_OUTPUT_VIDEO | OBS_OUTPUT_ENCODED;
	info.get_name = outName;
	info.create = outCreate;
	info.destroy = outDestroy;
	info.start = outStart;
	info.stop = outStop;
	info.encoded_packet = outPacket;
	info.encoded_video_codecs = "h264";
	obs_register_output(&info);
}

VideoPreview::VideoPreview(PacketFn fn) : sink(std::move(fn)) {}

VideoPreview::~VideoPreview()
{
	stop();
}

bool VideoPreview::start()
{
	if (running)
		return true;

	obs_data_t *es = obs_data_create();
	obs_data_set_string(es, "rate_control", "CBR");
	obs_data_set_int(es, "bitrate", 1200);
	obs_data_set_int(es, "keyint_sec", 1);
	obs_data_set_string(es, "preset", "veryfast");
	obs_data_set_string(es, "profile", "baseline");
	obs_data_set_string(es, "tune", "zerolatency");
	obs_data_set_string(es, "x264opts", "bframes=0");
	encoder = obs_video_encoder_create("obs_x264", "obs-director-preview-x264", es, nullptr);
	obs_data_release(es);
	if (!encoder) {
		obs_log(LOG_WARNING, "preview: x264 encoder unavailable");
		return false;
	}

	obs_encoder_set_scaled_size(encoder, 640, 360);
	obs_encoder_set_video(encoder, obs_get_video());

	struct obs_video_info ovi;
	if (obs_get_video_info(&ovi) && ovi.fps_den) {
		double fps = double(ovi.fps_num) / double(ovi.fps_den);
		uint32_t divisor = uint32_t(std::floor(fps / 24.0 + 1e-6));
		if (divisor < 1)
			divisor = 1;
		obs_encoder_set_frame_rate_divisor(encoder, divisor);
	}

	output = obs_output_create("obs_director_preview", "obs-director-preview", nullptr, nullptr);
	if (!output) {
		obs_encoder_release(encoder);
		encoder = nullptr;
		return false;
	}
	obs_output_set_video_encoder(output, encoder);

	auto *impl = static_cast<PreviewOutput *>(obs_obj_get_data(output));
	if (impl) {
		impl->cb = onPacket;
		impl->param = this;
	}

	sentConfig = false;
	running = obs_output_start(output);
	if (!running) {
		obs_log(LOG_WARNING, "preview: output failed to start");
		stop();
	}
	return running;
}

void VideoPreview::stop()
{
	if (output) {
		auto *impl = static_cast<PreviewOutput *>(obs_obj_get_data(output));
		if (impl)
			impl->cb = nullptr;
		if (running)
			obs_output_stop(output);
		obs_output_release(output);
		output = nullptr;
	}
	if (encoder) {
		obs_encoder_release(encoder);
		encoder = nullptr;
	}
	running = false;
}

void VideoPreview::onPacket(void *param, struct encoder_packet *pkt)
{
	auto *self = static_cast<VideoPreview *>(param);
	if (pkt->type != OBS_ENCODER_VIDEO || !self->sink)
		return;

	if (!self->sentConfig || pkt->keyframe) {
		uint8_t *extra = nullptr;
		size_t extraSize = 0;
		if (self->encoder && obs_encoder_get_extra_data(self->encoder, &extra, &extraSize) && extraSize) {
			self->sink(extra, extraSize, 0, false, true);
			self->sentConfig = true;
		}
	}

	int64_t ptsUs = 0;
	if (pkt->timebase_den)
		ptsUs = pkt->pts * 1000000LL * pkt->timebase_num / pkt->timebase_den;
	self->sink(pkt->data, pkt->size, ptsUs, pkt->keyframe, false);
}
