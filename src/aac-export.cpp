/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>
*/

#include "aac-export.hpp"

#include <QFile>

#include <algorithm>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
}

#ifdef _WIN32
#include <windows.h>
#endif

namespace {

constexpr int kBitrate = 192000;

/* Everything FFmpeg hands out, freed in one place whatever happens. */
struct Job {
	AVFormatContext *in = nullptr;
	AVCodecContext *dec = nullptr;
	AVFormatContext *out = nullptr;
	AVCodecContext *enc = nullptr;
	SwrContext *swr = nullptr;
	AVAudioFifo *fifo = nullptr;
	AVPacket *pkt = nullptr;
	AVPacket *outPkt = nullptr;
	AVFrame *frame = nullptr;
	AVFrame *conv = nullptr;
	AVFrame *chunk = nullptr;
	int64_t nextPts = 0;

	~Job()
	{
		av_frame_free(&chunk);
		av_frame_free(&conv);
		av_frame_free(&frame);
		av_packet_free(&outPkt);
		av_packet_free(&pkt);
		if (fifo)
			av_audio_fifo_free(fifo);
		swr_free(&swr);
		avcodec_free_context(&enc);
		if (out) {
			if (out->pb)
				avio_closep(&out->pb);
			avformat_free_context(out);
		}
		avcodec_free_context(&dec);
		avformat_close_input(&in);
	}

	/* Sends one frame (or nullptr to flush) to the encoder and writes what comes out. */
	bool encode(AVFrame *f)
	{
		if (f) {
			f->pts = nextPts;
			nextPts += f->nb_samples;
		}
		int r = avcodec_send_frame(enc, f);
		if (r < 0 && r != AVERROR_EOF)
			return false;
		while ((r = avcodec_receive_packet(enc, outPkt)) >= 0) {
			av_packet_rescale_ts(outPkt, enc->time_base, out->streams[0]->time_base);
			outPkt->stream_index = 0;
			if (av_interleaved_write_frame(out, outPkt) < 0)
				return false;
		}
		return r == AVERROR(EAGAIN) || r == AVERROR_EOF;
	}

	/* Encodes whole encoder frames from the fifo; with [all], the short last one too. */
	bool drain(bool all)
	{
		const int size = enc->frame_size > 0 ? enc->frame_size : 1024;
		while (av_audio_fifo_size(fifo) >= size || (all && av_audio_fifo_size(fifo) > 0)) {
			const int n = std::min(size, av_audio_fifo_size(fifo));
			av_frame_unref(chunk);
			chunk->nb_samples = n;
			chunk->format = enc->sample_fmt;
			chunk->sample_rate = enc->sample_rate;
			if (av_channel_layout_copy(&chunk->ch_layout, &enc->ch_layout) < 0 ||
			    av_frame_get_buffer(chunk, 0) < 0)
				return false;
			if (av_audio_fifo_read(fifo, reinterpret_cast<void **>(chunk->data), n) < n)
				return false;
			if (!encode(chunk))
				return false;
		}
		return true;
	}

	/* Resamples one decoded frame (or the resampler's tail, for nullptr) into the fifo. */
	bool resample(const AVFrame *f)
	{
		const int want = swr_get_out_samples(swr, f ? f->nb_samples : 0);
		if (want <= 0)
			return true;
		av_frame_unref(conv);
		conv->nb_samples = want;
		conv->format = enc->sample_fmt;
		conv->sample_rate = enc->sample_rate;
		if (av_channel_layout_copy(&conv->ch_layout, &enc->ch_layout) < 0 || av_frame_get_buffer(conv, 0) < 0)
			return false;
		const int got = swr_convert(swr, conv->data, want,
					    f ? const_cast<const uint8_t **>(f->extended_data) : nullptr,
					    f ? f->nb_samples : 0);
		if (got < 0)
			return false;
		return got == 0 || av_audio_fifo_write(fifo, reinterpret_cast<void **>(conv->data), got) == got;
	}
};

int pickRate(int rate)
{
	/* the rates AAC allows; anything else is resampled to 48 kHz */
	for (int r : {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000})
		if (rate == r)
			return r;
	return 48000;
}

/* The .m4a container, with its index at the front so a player can start before it has it all. */
bool openOutput(Job &j, const std::string &outPath, AVStream **dst)
{
	if (avformat_alloc_output_context2(&j.out, nullptr, "ipod", outPath.c_str()) < 0 || !j.out)
		return false;
	*dst = avformat_new_stream(j.out, nullptr);
	return *dst != nullptr;
}

bool startOutput(Job &j, const std::string &outPath)
{
	if (avio_open(&j.out->pb, outPath.c_str(), AVIO_FLAG_WRITE) < 0)
		return false;
	AVDictionary *opts = nullptr;
	av_dict_set(&opts, "movflags", "+faststart", 0);
	const int r = avformat_write_header(j.out, &opts);
	av_dict_free(&opts);
	return r >= 0;
}

} // namespace

bool extractAac(const std::string &inPath, const std::string &outPath, const std::function<bool(double)> &progress,
		std::string &error)
{
	Job j;
	auto fail = [&](const char *why) {
		error = why;
		return false;
	};

	if (avformat_open_input(&j.in, inPath.c_str(), nullptr, nullptr) < 0)
		return fail("open_failed");
	if (avformat_find_stream_info(j.in, nullptr) < 0)
		return fail("open_failed");
	const AVCodec *decoder = nullptr;
	const int stream = av_find_best_stream(j.in, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
	if (stream < 0)
		return fail("no_audio");
	AVStream *src = j.in->streams[stream];
	for (unsigned i = 0; i < j.in->nb_streams; i++)
		if (int(i) != stream)
			j.in->streams[i]->discard = AVDISCARD_ALL;

	/* how far through: the stream's own length, else the file's */
	double total = 0;
	if (src->duration > 0)
		total = double(src->duration) * av_q2d(src->time_base);
	else if (j.in->duration > 0)
		total = double(j.in->duration) / AV_TIME_BASE;
	const double start = src->start_time != AV_NOPTS_VALUE ? double(src->start_time) * av_q2d(src->time_base) : 0;
	double reported = -1;
	bool cancelled = false;
	auto report = [&](int64_t pts) {
		if (total <= 0 || pts == AV_NOPTS_VALUE)
			return true;
		const double at = (double(pts) * av_q2d(src->time_base) - start) / total;
		if (at - reported < 0.005)
			return true;
		reported = at;
		if (progress(std::min(1.0, std::max(0.0, at))))
			return true;
		cancelled = true;
		return false;
	};

	j.pkt = av_packet_alloc();
	if (!j.pkt)
		return fail("out_of_memory");

	/* ---- AAC already (what OBS records): copy it across as it is ---- */
	if (src->codecpar->codec_id == AV_CODEC_ID_AAC) {
		AVStream *dst = nullptr;
		if (!openOutput(j, outPath, &dst) || avcodec_parameters_copy(dst->codecpar, src->codecpar) < 0)
			return fail("write_failed");
		dst->codecpar->codec_tag = 0;
		const int rate = src->codecpar->sample_rate > 0 ? src->codecpar->sample_rate : 48000;
		const int frame = src->codecpar->frame_size > 0 ? src->codecpar->frame_size : 1024;
		dst->time_base = AVRational{1, rate};
		if (!startOutput(j, outPath))
			return fail("write_failed");
		/* Each AAC packet is one frame of samples: time them by count, so the file plays evenly
		 * whatever the source container's clock (Matroska's is in milliseconds). */
		int64_t n = 0;
		while (av_read_frame(j.in, j.pkt) >= 0) {
			if (j.pkt->stream_index == stream && j.pkt->size > 0) {
				if (!report(j.pkt->pts)) {
					av_packet_unref(j.pkt);
					return fail("cancelled");
				}
				j.pkt->stream_index = 0;
				j.pkt->pts = j.pkt->dts = n * frame;
				j.pkt->duration = frame;
				j.pkt->pos = -1;
				n++;
				if (av_interleaved_write_frame(j.out, j.pkt) < 0)
					return fail("write_failed");
			}
			av_packet_unref(j.pkt);
		}
		if (n == 0)
			return fail("no_audio");
		if (av_write_trailer(j.out) < 0)
			return fail("write_failed");
		progress(1.0);
		return true;
	}

	/* ---- anything else: decode it and encode AAC ---- */
	if (!decoder)
		return fail("no_audio");
	j.dec = avcodec_alloc_context3(decoder);
	if (!j.dec || avcodec_parameters_to_context(j.dec, src->codecpar) < 0)
		return fail("decoder_failed");
	j.dec->pkt_timebase = src->time_base;
	if (avcodec_open2(j.dec, decoder, nullptr) < 0)
		return fail("decoder_failed");

	const AVCodec *encoder = avcodec_find_encoder(AV_CODEC_ID_AAC);
	if (!encoder)
		return fail("no_aac_encoder");
	j.enc = avcodec_alloc_context3(encoder);
	if (!j.enc)
		return fail("encoder_failed");
	j.enc->bit_rate = kBitrate;
	j.enc->sample_rate = pickRate(j.dec->sample_rate);
	j.enc->sample_fmt = AV_SAMPLE_FMT_FLTP;
	j.enc->time_base = AVRational{1, j.enc->sample_rate};
	av_channel_layout_default(&j.enc->ch_layout, 2);

	AVStream *dst = nullptr;
	if (!openOutput(j, outPath, &dst))
		return fail("write_failed");
	if (j.out->oformat->flags & AVFMT_GLOBALHEADER)
		j.enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
	if (avcodec_open2(j.enc, encoder, nullptr) < 0)
		return fail("encoder_failed");
	if (avcodec_parameters_from_context(dst->codecpar, j.enc) < 0)
		return fail("encoder_failed");
	dst->time_base = j.enc->time_base;
	if (!startOutput(j, outPath))
		return fail("write_failed");

	AVChannelLayout inLayout;
	if (j.dec->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || j.dec->ch_layout.nb_channels <= 0)
		av_channel_layout_default(&inLayout,
					  j.dec->ch_layout.nb_channels > 0 ? j.dec->ch_layout.nb_channels : 2);
	else if (av_channel_layout_copy(&inLayout, &j.dec->ch_layout) < 0)
		return fail("decoder_failed");
	const int made = swr_alloc_set_opts2(&j.swr, &j.enc->ch_layout, j.enc->sample_fmt, j.enc->sample_rate,
					     &inLayout, j.dec->sample_fmt, j.dec->sample_rate, 0, nullptr);
	av_channel_layout_uninit(&inLayout);
	if (made < 0 || swr_init(j.swr) < 0)
		return fail("resample_failed");

	j.fifo = av_audio_fifo_alloc(j.enc->sample_fmt, j.enc->ch_layout.nb_channels, 4096);
	j.outPkt = av_packet_alloc();
	j.frame = av_frame_alloc();
	j.conv = av_frame_alloc();
	j.chunk = av_frame_alloc();
	if (!j.fifo || !j.outPkt || !j.frame || !j.conv || !j.chunk)
		return fail("out_of_memory");

	auto decodeAll = [&]() {
		int r;
		while ((r = avcodec_receive_frame(j.dec, j.frame)) >= 0) {
			if (!j.resample(j.frame) || !j.drain(false) || !report(j.frame->pts))
				return false;
			av_frame_unref(j.frame);
		}
		return r == AVERROR(EAGAIN) || r == AVERROR_EOF;
	};

	while (av_read_frame(j.in, j.pkt) >= 0) {
		if (j.pkt->stream_index == stream) {
			/* a damaged packet is skipped, not the end of the job */
			if (avcodec_send_packet(j.dec, j.pkt) >= 0 && !decodeAll()) {
				av_packet_unref(j.pkt);
				return fail(cancelled ? "cancelled" : "convert_failed");
			}
		}
		av_packet_unref(j.pkt);
	}
	avcodec_send_packet(j.dec, nullptr);
	if (!decodeAll() || !j.resample(nullptr) || !j.drain(true) || !j.encode(nullptr))
		return fail(cancelled ? "cancelled" : "convert_failed");
	if (av_write_trailer(j.out) < 0)
		return fail("write_failed");
	progress(1.0);
	return true;
}

/* ---------------- on its own thread ---------------- */

AacExport::AacExport(QObject *parent) : QObject(parent) {}

AacExport::~AacExport()
{
	stop = true;
	if (worker.joinable())
		worker.join();
}

bool AacExport::available()
{
#ifdef _WIN32
	/* The plugin loads OBS's FFmpeg on first use: it must be the version this was built with,
	 * or nothing is loaded and Save as AAC says it can't here. */
	static const int ok = [] {
		const std::string names[] = {"avutil-" + std::to_string(LIBAVUTIL_VERSION_MAJOR) + ".dll",
					     "avcodec-" + std::to_string(LIBAVCODEC_VERSION_MAJOR) + ".dll",
					     "avformat-" + std::to_string(LIBAVFORMAT_VERSION_MAJOR) + ".dll",
					     "swresample-" + std::to_string(LIBSWRESAMPLE_VERSION_MAJOR) + ".dll"};
		for (const std::string &n : names)
			if (!LoadLibraryA(n.c_str()))
				return 0;
		return 1;
	}();
	return ok == 1;
#else
	return true;
#endif
}

bool AacExport::start(const QString &fileName, const QString &inPath, const QString &outPath)
{
	if (running)
		return false;
	if (worker.joinable())
		worker.join();
	running = true;
	stop = false;
	name = fileName;
	worker = std::thread([this, fileName, in = inPath.toStdString(), out = outPath.toStdString(), outPath]() {
		const std::string part = out + ".part";
		std::string error;
		int last = -1;
		bool ok = extractAac(
			in, part,
			[&](double at) {
				const int pct = int(at * 100);
				if (pct != last) {
					last = pct;
					emit progress(fileName, pct);
				}
				return !stop.load();
			},
			error);
		/* finished under a temporary name, so a half-made file is never taken for a whole one */
		if (ok) {
			QFile::remove(outPath);
			ok = QFile::rename(QString::fromStdString(part), outPath);
			if (!ok)
				error = "write_failed";
		}
		if (!ok)
			QFile::remove(QString::fromStdString(part));
		if (stop && !ok)
			error = "cancelled";
		running = false;
		emit finished(fileName, ok, QString::fromStdString(error));
	});
	return true;
}
