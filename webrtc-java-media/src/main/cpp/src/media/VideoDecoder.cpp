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

#include <string>

namespace
{
	// yuv420p is what WebRTC calls I420. yuvj420p has the same planes and
	// strides and differs only in the range the values cover, which is
	// metadata rather than layout, so it needs no conversion either.
	bool IsI420(int format)
	{
		return format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P;
	}

	// The kinds of hardware device the platform decodes video with, best
	// first. Whether FFmpeg was built with a decoder for one is another
	// matter, and is found out when the codec asks for it.
	std::vector<AVHWDeviceType> DeviceTypes()
	{
#if defined(__APPLE__)
		return { AV_HWDEVICE_TYPE_VIDEOTOOLBOX };
#elif defined(_WIN32)
		return { AV_HWDEVICE_TYPE_D3D12VA, AV_HWDEVICE_TYPE_D3D11VA };
#else
		return { AV_HWDEVICE_TYPE_CUDA };
#endif
	}

	bool IsFailure(int result)
	{
		return result < 0 && result != AVERROR(EAGAIN) && result != AVERROR_EOF;
	}

	std::string ErrorString(int error)
	{
		char text[AV_ERROR_MAX_STRING_SIZE] = {};

		av_strerror(error, text, sizeof(text));

		return text;
	}
}

namespace ffmpeg
{
	VideoDecoder::~VideoDecoder()
	{
		Close();
	}

	int VideoDecoder::Open(const AVStream * stream, bool hardware)
	{
		Close();

		if (stream == nullptr) {
			return AVERROR(EINVAL);
		}

		stream_ = stream;

		int result = OpenContext(hardware);

		if (result < 0 && hardware) {
			av_log(nullptr, AV_LOG_INFO, "No hardware video decoder (%s), decoding in software\n",
					ErrorString(result).c_str());

			result = OpenContext(false);
		}
		if (result < 0) {
			Close();

			return result;
		}

		decoded_ = av_frame_alloc();
		transferred_ = av_frame_alloc();

		if (decoded_ == nullptr || transferred_ == nullptr) {
			Close();

			return AVERROR(ENOMEM);
		}

		time_base_ = stream->time_base;

		// A decoder that is set up for hardware is on probation until it has
		// produced its first frame.
		probation_ = hardware_;

		return 0;
	}

	int VideoDecoder::OpenContext(bool hardware)
	{
		const AVCodec * codec = avcodec_find_decoder(stream_->codecpar->codec_id);

		if (codec == nullptr) {
			return AVERROR_DECODER_NOT_FOUND;
		}

		codec_context_ = avcodec_alloc_context3(codec);

		if (codec_context_ == nullptr) {
			return AVERROR(ENOMEM);
		}

		int result = avcodec_parameters_to_context(codec_context_, stream_->codecpar);

		if (result < 0) {
			avcodec_free_context(&codec_context_);

			return result;
		}

		// Without this the decoder cannot put a meaningful timestamp on a
		// frame, and every frame would come back with AV_NOPTS_VALUE.
		codec_context_->pkt_timebase = stream_->time_base;

		if (hardware) {
			if (!SetUpHardware(codec)) {
				avcodec_free_context(&codec_context_);

				return AVERROR(ENOSYS);
			}

			// The hardware does the decoding; threads would only wait for it.
			codec_context_->thread_count = 1;
		}
		else {
			// 0 lets libavcodec pick a thread count for the machine. Decoding
			// is the one part of playback that can genuinely use several cores.
			codec_context_->thread_count = 0;
		}

		result = avcodec_open2(codec_context_, codec, nullptr);

		if (result < 0) {
			avcodec_free_context(&codec_context_);

			hardware_format_ = AV_PIX_FMT_NONE;

			return result;
		}

		hardware_ = hardware;

		return 0;
	}

	bool VideoDecoder::SetUpHardware(const AVCodec * codec)
	{
		for (AVHWDeviceType type : DeviceTypes()) {
			for (int index = 0;; index++) {
				const AVCodecHWConfig * config = avcodec_get_hw_config(codec, index);

				if (config == nullptr) {
					break;
				}
				if (!(config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) || config->device_type != type) {
					continue;
				}

				AVBufferRef * device = nullptr;

				if (av_hwdevice_ctx_create(&device, type, nullptr, nullptr, 0) < 0) {
					// This kind of device is not there; the next may be.
					break;
				}

				codec_context_->hw_device_ctx = device;
				codec_context_->get_format = &VideoDecoder::ChooseFormat;
				codec_context_->opaque = this;

				hardware_format_ = config->pix_fmt;

				return true;
			}
		}

		return false;
	}

	AVPixelFormat VideoDecoder::ChooseFormat(AVCodecContext * context, const AVPixelFormat * formats)
	{
		const VideoDecoder * decoder = static_cast<const VideoDecoder *>(context->opaque);

		for (const AVPixelFormat * format = formats; *format != AV_PIX_FMT_NONE; format++) {
			if (*format == decoder->hardware_format_) {
				return *format;
			}
		}

		// The decoder does not offer the hardware format for this stream:
		// a profile or a size the hardware does not take. Software it is.
		return avcodec_default_get_format(context, formats);
	}

	void VideoDecoder::Close()
	{
		DropKeptPackets();
		DropPending();

		if (decoded_ != nullptr) {
			av_frame_free(&decoded_);
		}
		if (transferred_ != nullptr) {
			av_frame_free(&transferred_);
		}
		if (sws_context_ != nullptr) {
			sws_freeContext(sws_context_);

			sws_context_ = nullptr;
		}

		// Frames still out with WebRTC keep their buffers, and the pool goes
		// when the last of them comes back.
		av_buffer_pool_uninit(&pool_);

		pool_width_ = 0;
		pool_height_ = 0;

		if (codec_context_ != nullptr) {
			avcodec_free_context(&codec_context_);
		}

		stream_ = nullptr;
		hardware_ = false;
		hardware_format_ = AV_PIX_FMT_NONE;
		probation_ = false;
		time_base_ = { 0, 1 };
	}

	void VideoDecoder::Flush()
	{
		if (codec_context_ != nullptr) {
			avcodec_flush_buffers(codec_context_);
		}

		// What was kept belongs to the position that was left.
		DropKeptPackets();
		DropPending();
	}

	int VideoDecoder::SendPacket(const AVPacket * packet)
	{
		if (codec_context_ == nullptr) {
			return AVERROR(EINVAL);
		}

		if (probation_ && packet != nullptr) {
			KeepPacket(packet);
		}

		int result = avcodec_send_packet(codec_context_, packet);

		if (hardware_ && IsFailure(result)) {
			result = FallBackToSoftware(result);

			if (packet == nullptr && result == AVERROR(EAGAIN)) {
				// The drain the caller started was the hardware decoder's.
				result = avcodec_send_packet(codec_context_, nullptr);
			}
		}

		return result;
	}

	int VideoDecoder::ReceiveFrame(AVFrame ** frame, int64_t * timestamp_us)
	{
		if (codec_context_ == nullptr || decoded_ == nullptr) {
			return AVERROR(EINVAL);
		}

		if (!pending_.empty()) {
			*frame = pending_.front().frame;
			*timestamp_us = pending_.front().timestamp_us;

			pending_.pop_front();

			return 0;
		}

		int result = ReceiveFromCodec(frame, timestamp_us);

		if (hardware_ && IsFailure(result)) {
			// Decoding goes on in software; the pictures of the packets it
			// takes up again come with the calls that follow.
			return FallBackToSoftware(result);
		}

		return result;
	}

	int VideoDecoder::ReceiveFromCodec(AVFrame ** frame, int64_t * timestamp_us)
	{
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

		const bool hardware_frame = hardware_format_ != AV_PIX_FMT_NONE && decoded_->format == hardware_format_;

		if (hardware_frame) {
			// The picture is in the memory of the hardware decoder. Bring it
			// into system memory, as NV12, and convert it like any other
			// format that is not I420.
			//
			// The system-memory frame stays between pictures: a buffer of
			// this size is a fresh allocation every time it is dropped, and
			// the pages of a fresh allocation cost more to fault in than the
			// copy into them. The buffer is made for the size of the decoder's
			// surfaces, which is as much as a transfer may copy (Direct3D 12
			// copies all of it, padding included), so it is kept only for
			// as long as those, the picture and the format stay as they are.
			const AVHWFramesContext * frames = reinterpret_cast<const AVHWFramesContext *>(decoded_->hw_frames_ctx->data);

			if (transferred_->buf[0] != nullptr && (transferred_->width != decoded_->width
					|| transferred_->height != decoded_->height || transferred_->format != frames->sw_format
					|| transfer_width_ != frames->width || transfer_height_ != frames->height)) {
				av_frame_unref(transferred_);
			}

			const bool allocated = transferred_->buf[0] == nullptr;

			result = av_hwframe_transfer_data(transferred_, decoded_, 0);

			if (result >= 0) {
				if (allocated) {
					transfer_width_ = frames->width;
					transfer_height_ = frames->height;
				}

				result = ConvertToI420(transferred_, frame);
			}

			av_frame_unref(decoded_);
		}
		else if (IsI420(decoded_->format)) {
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
			result = 0;
		}
		else {
			result = ConvertToI420(decoded_, frame);

			av_frame_unref(decoded_);
		}

		if (result >= 0 && probation_) {
			// The first picture is out: either the hardware decoded it, or the
			// decoder offered no hardware format and it was software that did.
			probation_ = false;

			DropKeptPackets();

			if (!hardware_frame) {
				hardware_ = false;
			}
		}

		return result;
	}

	int VideoDecoder::FallBackToSoftware(int error)
	{
		av_log(nullptr, AV_LOG_WARNING, "Hardware video decoding failed (%s), decoding in software\n",
				ErrorString(error).c_str());

		avcodec_free_context(&codec_context_);

		hardware_ = false;
		hardware_format_ = AV_PIX_FMT_NONE;

		int result = OpenContext(false);

		if (result < 0) {
			DropKeptPackets();

			return result;
		}

		// Nothing has come out of the hardware yet: the packets it was sent
		// are decoded again, so that none of them is lost. After a picture
		// the stream goes on from its next key frame.
		std::vector<AVPacket *> kept;
		kept.swap(kept_);

		probation_ = false;

		for (AVPacket * packet : kept) {
			int sent = avcodec_send_packet(codec_context_, packet);

			while (sent == AVERROR(EAGAIN)) {
				// The decoder wants its pictures taken before it takes more.
				AVFrame * frame = nullptr;
				int64_t timestamp_us = 0;

				if (ReceiveFromCodec(&frame, &timestamp_us) < 0) {
					break;
				}

				pending_.push_back({ frame, timestamp_us });

				sent = avcodec_send_packet(codec_context_, packet);
			}

			av_packet_free(&packet);
		}

		return AVERROR(EAGAIN);
	}

	void VideoDecoder::KeepPacket(const AVPacket * packet)
	{
		AVPacket * copy = kept_.size() < kMaxKeptPackets ? av_packet_clone(packet) : nullptr;

		if (copy == nullptr) {
			// Too many to keep: the hardware has been given its chance.
			probation_ = false;

			DropKeptPackets();

			return;
		}

		kept_.push_back(copy);
	}

	void VideoDecoder::DropKeptPackets()
	{
		for (AVPacket * packet : kept_) {
			av_packet_free(&packet);
		}

		kept_.clear();
	}

	void VideoDecoder::DropPending()
	{
		for (Pending & pending : pending_) {
			av_frame_free(&pending.frame);
		}

		pending_.clear();
	}

	bool VideoDecoder::IsHardware() const
	{
		return hardware_.load();
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

		AVFrame * converted = nullptr;

		int error = AllocateI420(source->width, source->height, &converted);

		if (error < 0) {
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

	int VideoDecoder::AllocateI420(int width, int height, AVFrame ** result)
	{
		constexpr int kAlign = 32;

		if (pool_ == nullptr || pool_width_ != width || pool_height_ != height) {
			av_buffer_pool_uninit(&pool_);

			const int size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, width, height, kAlign);

			if (size < 0) {
				return size;
			}

			pool_ = av_buffer_pool_init(size, av_buffer_allocz);
			pool_width_ = width;
			pool_height_ = height;

			if (pool_ == nullptr) {
				return AVERROR(ENOMEM);
			}
		}

		AVFrame * frame = av_frame_alloc();

		if (frame == nullptr) {
			return AVERROR(ENOMEM);
		}

		// The buffer goes back to the pool when the last reference to the
		// frame is dropped, so the memory of a picture is reused for the
		// next ones rather than allocated, and faulted in, each time.
		frame->buf[0] = av_buffer_pool_get(pool_);

		if (frame->buf[0] == nullptr) {
			av_frame_free(&frame);

			return AVERROR(ENOMEM);
		}

		frame->format = AV_PIX_FMT_YUV420P;
		frame->width = width;
		frame->height = height;

		int error = av_image_fill_arrays(frame->data, frame->linesize, frame->buf[0]->data,
				AV_PIX_FMT_YUV420P, width, height, kAlign);

		if (error < 0) {
			av_frame_free(&frame);

			return error;
		}

		*result = frame;

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
