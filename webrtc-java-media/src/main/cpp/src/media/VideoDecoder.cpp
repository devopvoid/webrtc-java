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

#include "media/VideoDecoder.h"

extern "C" {
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
}

namespace
{
	// yuv420p is what WebRTC calls I420. yuvj420p has the same planes and
	// strides and differs only in the range the values cover, which is
	// metadata rather than layout, so it needs no conversion either.
	bool IsI420(int format)
	{
		return format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P;
	}
}

namespace ffmpeg
{
	VideoDecoder::~VideoDecoder()
	{
		Close();
	}

	int VideoDecoder::Open(const AVStream * stream)
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

		// Without this the decoder cannot put a meaningful timestamp on a
		// frame, and every frame would come back with AV_NOPTS_VALUE.
		codec_context_->pkt_timebase = stream->time_base;

		// 0 lets libavcodec pick a thread count for the machine. Decoding is
		// the one part of playback that can genuinely use several cores.
		codec_context_->thread_count = 0;

		result = avcodec_open2(codec_context_, codec, nullptr);

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

	void VideoDecoder::Close()
	{
		if (decoded_ != nullptr) {
			av_frame_free(&decoded_);
		}
		if (sws_context_ != nullptr) {
			sws_freeContext(sws_context_);

			sws_context_ = nullptr;
		}
		if (codec_context_ != nullptr) {
			avcodec_free_context(&codec_context_);
		}

		time_base_ = { 0, 1 };
	}

	void VideoDecoder::Flush()
	{
		if (codec_context_ != nullptr) {
			avcodec_flush_buffers(codec_context_);
		}
	}

	int VideoDecoder::SendPacket(const AVPacket * packet)
	{
		if (codec_context_ == nullptr) {
			return AVERROR(EINVAL);
		}

		return avcodec_send_packet(codec_context_, packet);
	}

	int VideoDecoder::ReceiveFrame(AVFrame ** frame, int64_t * timestamp_us)
	{
		if (codec_context_ == nullptr || decoded_ == nullptr) {
			return AVERROR(EINVAL);
		}

		int result = avcodec_receive_frame(codec_context_, decoded_);

		if (result < 0) {
			return result;
		}

		// best_effort_timestamp is what libavcodec makes of a stream whose
		// packets carry no presentation time of their own.
		int64_t pts = decoded_->best_effort_timestamp != AV_NOPTS_VALUE
				? decoded_->best_effort_timestamp : decoded_->pts;

		*timestamp_us = pts != AV_NOPTS_VALUE
				? av_rescale_q(pts, time_base_, AV_TIME_BASE_Q) : 0;

		if (IsI420(decoded_->format)) {
			// Hand over a reference to the decoded picture rather than a copy
			// of it. The caller drops that reference once WebRTC is done.
			AVFrame * reference = av_frame_alloc();

			if (reference == nullptr) {
				av_frame_unref(decoded_);

				return AVERROR(ENOMEM);
			}

			result = av_frame_ref(reference, decoded_);

			av_frame_unref(decoded_);

			if (result < 0) {
				av_frame_free(&reference);

				return result;
			}

			*frame = reference;

			return 0;
		}

		result = ConvertToI420(decoded_, frame);

		av_frame_unref(decoded_);

		return result;
	}

	int VideoDecoder::ConvertToI420(const AVFrame * source, AVFrame ** result)
	{
		sws_context_ = sws_getCachedContext(sws_context_,
				source->width, source->height,
				static_cast<AVPixelFormat>(source->format),
				source->width, source->height, AV_PIX_FMT_YUV420P,
				SWS_BILINEAR, nullptr, nullptr, nullptr);

		if (sws_context_ == nullptr) {
			return AVERROR(EINVAL);
		}

		AVFrame * converted = av_frame_alloc();

		if (converted == nullptr) {
			return AVERROR(ENOMEM);
		}

		converted->format = AV_PIX_FMT_YUV420P;
		converted->width = source->width;
		converted->height = source->height;

		int error = av_frame_get_buffer(converted, 0);

		if (error < 0) {
			av_frame_free(&converted);

			return error;
		}

		error = sws_scale(sws_context_, source->data, source->linesize, 0,
				source->height, converted->data, converted->linesize);

		if (error < 0) {
			av_frame_free(&converted);

			return error;
		}

		*result = converted;

		return 0;
	}

	int VideoDecoder::GetWidth() const
	{
		return codec_context_ != nullptr ? codec_context_->width : 0;
	}

	int VideoDecoder::GetHeight() const
	{
		return codec_context_ != nullptr ? codec_context_->height : 0;
	}
}
