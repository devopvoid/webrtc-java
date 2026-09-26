/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "media/AudioDecoder.h"

extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
}

namespace ffmpeg
{
	AudioDecoder::~AudioDecoder()
	{
		Close();
	}

	int AudioDecoder::Open(const AVStream * stream)
	{
		Close();

		if (stream == nullptr) {
			return AVERROR(EINVAL);
		}

		const AVCodec * codec = avcodec_find_decoder(stream->codecpar->codec_id);

		if (codec == nullptr) {
			return AVERROR_DECODER_NOT_FOUND;
		}

		codec_context_ = avcodec_alloc_context3(codec);

		if (codec_context_ == nullptr) {
			return AVERROR(ENOMEM);
		}

		int result = avcodec_parameters_to_context(codec_context_, stream->codecpar);

		if (result < 0) {
			Close();

			return result;
		}

		codec_context_->pkt_timebase = stream->time_base;

		result = avcodec_open2(codec_context_, codec, nullptr);

		if (result < 0) {
			Close();

			return result;
		}

		// WebRTC takes mono or stereo, so anything wider is downmixed. The
		// downmix itself is swresample's job.
		// The output keeps this channel count for the whole stream, even if
		// the stream's own count changes later: the pending buffer and the
		// source it feeds both assume one.
		channels_ = codec_context_->ch_layout.nb_channels >= 2 ? 2 : 1;

		result = ConfigureResampler(&codec_context_->ch_layout,
				codec_context_->sample_fmt, codec_context_->sample_rate);

		if (result < 0) {
			Close();

			return result;
		}

		decoded_ = av_frame_alloc();

		if (decoded_ == nullptr) {
			Close();

			return AVERROR(ENOMEM);
		}

		time_base_ = stream->time_base;

		return 0;
	}

	void AudioDecoder::Close()
	{
		if (decoded_ != nullptr) {
			av_frame_free(&decoded_);
		}
		if (swr_context_ != nullptr) {
			swr_free(&swr_context_);
		}
		if (codec_context_ != nullptr) {
			avcodec_free_context(&codec_context_);
		}

		av_channel_layout_uninit(&in_layout_);
		in_format_ = -1;
		in_rate_ = 0;

		pending_.clear();

		time_base_ = { 0, 1 };
		channels_ = 0;
		next_timestamp_us_ = 0;
		have_timestamp_ = false;
		resampler_drained_ = false;
	}

	void AudioDecoder::Flush()
	{
		if (codec_context_ != nullptr) {
			avcodec_flush_buffers(codec_context_);
		}
		if (swr_context_ != nullptr) {
			// What swresample holds back belongs to the old position as much
			// as what is pending does. Initializing it again drops that and
			// keeps its setup.
			swr_init(swr_context_);
		}

		pending_.clear();
		resampler_drained_ = false;

		// The next frame decoded tells where the audio now starts.
		have_timestamp_ = false;
	}

	int AudioDecoder::SendPacket(const AVPacket * packet)
	{
		if (codec_context_ == nullptr) {
			return AVERROR(EINVAL);
		}

		return avcodec_send_packet(codec_context_, packet);
	}

	int AudioDecoder::ReceiveChunk(std::vector<int16_t> & chunk, int64_t * timestamp_us)
	{
		if (codec_context_ == nullptr || decoded_ == nullptr) {
			return AVERROR(EINVAL);
		}

		const size_t wanted = static_cast<size_t>(kFramesPerChunk) * channels_;

		while (pending_.size() < wanted) {
			int result = avcodec_receive_frame(codec_context_, decoded_);

			if (result == AVERROR_EOF) {
				if (!resampler_drained_) {
					// swresample holds the last few samples back, waiting for
					// more input to filter them with. There is none, so they
					// have to be asked for, or the stream ends short.
					resampler_drained_ = true;

					result = DrainResampler();

					if (result < 0) {
						return result;
					}

					continue;
				}
				if (pending_.empty()) {
					return AVERROR_EOF;
				}

				// The last chunk of a stream rarely lands on a 10 ms boundary,
				// and WebRTC has no way to take a short one, so the remainder
				// is filled with silence.
				pending_.resize(wanted, 0);

				break;
			}
			if (result < 0) {
				return result;
			}

			result = Resample(decoded_);

			av_frame_unref(decoded_);

			if (result < 0) {
				return result;
			}
		}

		TakeChunk(chunk, timestamp_us);

		return 0;
	}

	int AudioDecoder::Resample(const AVFrame * frame)
	{
		if (!have_timestamp_) {
			int64_t pts = frame->best_effort_timestamp != AV_NOPTS_VALUE
					? frame->best_effort_timestamp : frame->pts;

			next_timestamp_us_ = pts != AV_NOPTS_VALUE
					? av_rescale_q(pts, time_base_, AV_TIME_BASE_Q) : 0;
			have_timestamp_ = true;
		}

		if (frame->format != in_format_ || frame->sample_rate != in_rate_
				|| av_channel_layout_compare(&frame->ch_layout, &in_layout_) != 0) {
			// The stream changed its format. What swresample still holds of
			// the old one comes out first, since it plays before this frame.
			int result = DrainResampler();

			if (result < 0) {
				return result;
			}

			result = ConfigureResampler(&frame->ch_layout, frame->format,
					frame->sample_rate);

			if (result < 0) {
				return result;
			}
		}

		// swresample holds samples back when rates differ, and those come out
		// of a later call, so the room needed is the delay plus this frame.
		int64_t delay = swr_get_delay(swr_context_, in_rate_);
		int64_t capacity = av_rescale_rnd(delay + frame->nb_samples, kSampleRate,
				in_rate_, AV_ROUND_UP);

		if (capacity <= 0) {
			return 0;
		}

		const size_t offset = pending_.size();

		pending_.resize(offset + static_cast<size_t>(capacity) * channels_);

		uint8_t * output = reinterpret_cast<uint8_t *>(pending_.data() + offset);

		int converted = swr_convert(swr_context_, &output, static_cast<int>(capacity),
				const_cast<const uint8_t **>(frame->data), frame->nb_samples);

		if (converted < 0) {
			pending_.resize(offset);

			return converted;
		}

		// swresample usually produces fewer samples than the room made for it.
		pending_.resize(offset + static_cast<size_t>(converted) * channels_);

		return 0;
	}

	int AudioDecoder::DrainResampler()
	{
		if (swr_context_ == nullptr) {
			return 0;
		}

		for (;;) {
			int capacity = swr_get_out_samples(swr_context_, 0);

			if (capacity <= 0) {
				return 0;
			}

			const size_t offset = pending_.size();

			pending_.resize(offset + static_cast<size_t>(capacity) * channels_);

			uint8_t * output = reinterpret_cast<uint8_t *>(pending_.data() + offset);

			// No input is what tells swresample to hand out what it holds.
			int converted = swr_convert(swr_context_, &output, capacity, nullptr, 0);

			if (converted < 0) {
				pending_.resize(offset);

				return converted;
			}

			pending_.resize(offset + static_cast<size_t>(converted) * channels_);

			if (converted == 0) {
				return 0;
			}
		}
	}

	int AudioDecoder::ConfigureResampler(const AVChannelLayout * layout, int format,
			int sample_rate)
	{
		if (layout == nullptr || layout->nb_channels <= 0 || sample_rate <= 0) {
			return AVERROR(EINVAL);
		}

		if (swr_context_ != nullptr) {
			swr_free(&swr_context_);
		}

		av_channel_layout_uninit(&in_layout_);
		in_format_ = -1;
		in_rate_ = 0;

		// A layout that only gives a channel count leaves swresample nothing
		// to build a downmix from, so it is read as the usual layout for that
		// many channels. What is remembered is the frame's own layout, so the
		// next frame compares equal to it.
		AVChannelLayout mix_layout = {};
		int result = 0;

		if (layout->order == AV_CHANNEL_ORDER_UNSPEC) {
			av_channel_layout_default(&mix_layout, layout->nb_channels);
		}
		else {
			result = av_channel_layout_copy(&mix_layout, layout);
		}

		if (result < 0) {
			return result;
		}

		AVChannelLayout out_layout = {};

		av_channel_layout_default(&out_layout, channels_);

		result = swr_alloc_set_opts2(&swr_context_,
				&out_layout, AV_SAMPLE_FMT_S16, kSampleRate,
				&mix_layout, static_cast<AVSampleFormat>(format), sample_rate,
				0, nullptr);

		av_channel_layout_uninit(&out_layout);
		av_channel_layout_uninit(&mix_layout);

		if (result < 0) {
			return result;
		}

		result = swr_init(swr_context_);

		if (result < 0) {
			swr_free(&swr_context_);

			return result;
		}

		result = av_channel_layout_copy(&in_layout_, layout);

		if (result < 0) {
			swr_free(&swr_context_);

			return result;
		}

		in_format_ = format;
		in_rate_ = sample_rate;

		return 0;
	}

	void AudioDecoder::TakeChunk(std::vector<int16_t> & chunk, int64_t * timestamp_us)
	{
		const size_t wanted = static_cast<size_t>(kFramesPerChunk) * channels_;

		chunk.assign(pending_.begin(), pending_.begin() + wanted);
		pending_.erase(pending_.begin(), pending_.begin() + wanted);

		*timestamp_us = next_timestamp_us_;

		// Exactly 10 ms on, whatever the container's own packet timing was.
		next_timestamp_us_ += 10000;
	}

	int AudioDecoder::GetChannels() const
	{
		return channels_;
	}
}
