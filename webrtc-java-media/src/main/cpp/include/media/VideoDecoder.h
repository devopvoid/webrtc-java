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

#ifndef WEBRTC_JAVA_MEDIA_VIDEO_DECODER_H_
#define WEBRTC_JAVA_MEDIA_VIDEO_DECODER_H_

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/hwcontext.h>
#include <libswscale/swscale.h>
}

#include <atomic>
#include <deque>
#include <vector>

namespace ffmpeg
{
	// Decodes one video stream into I420, which is the only pixel format
	// WebRTC takes.
	//
	// Almost all 8-bit H.264, VP8, VP9 and MPEG-4 content decodes to yuv420p
	// already, and such a frame is handed on untouched, so the decoded picture
	// reaches the encoder without a single copy. Anything else is converted
	// with swscale first, which costs one copy and is the price of the format
	// rather than of this design.
	//
	// Asked to, it decodes H.264 and VP9 on the media engine or GPU instead.
	// The pictures that come out are the same; they are read back into system
	// memory and converted, so the gain is processor time, not the copy. A
	// stream the hardware cannot decode is decoded in software, without the
	// caller noticing more than IsHardware turning false.
	class VideoDecoder
	{
		public:
			VideoDecoder() = default;
			~VideoDecoder();

			VideoDecoder(const VideoDecoder &) = delete;
			VideoDecoder & operator=(const VideoDecoder &) = delete;

			// Opens a decoder for the given stream, which must outlive this
			// decoder, in hardware if that is asked for and the platform has
			// a hardware decoder for the codec, and in software otherwise.
			// Returns 0 or a negative AVERROR.
			int Open(const AVStream * stream, bool hardware = false);

			void Close();

			// Drops everything the decoder holds, which is what a seek needs:
			// the frames in flight belong to the position that was left.
			void Flush();

			// Hands a packet to the decoder, or null to start draining at the
			// end of the stream. Returns 0 or a negative AVERROR.
			int SendPacket(const AVPacket * packet);

			// Takes the next decoded frame, in I420 and owned by the caller,
			// who frees it with av_frame_free.
			//
			// Returns 0 when a frame was produced, AVERROR(EAGAIN) when the
			// decoder needs another packet first, AVERROR_EOF once draining
			// has finished, or another negative AVERROR.
			int ReceiveFrame(AVFrame ** frame, int64_t * timestamp_us);

			int GetWidth() const;
			int GetHeight() const;

			// Whether the stream is decoded in hardware. True once the decoder
			// is set up for it; it turns false when the first frames show
			// that the hardware did not take the stream, or when it fails and
			// decoding goes on in software. Safe to call from any thread.
			bool IsHardware() const;

		private:
			// How many packets are kept, until the hardware has produced a
			// frame, to be decoded again in software if it fails by then.
			static constexpr size_t kMaxKeptPackets = 32;

			// How many packets may be passed over while waiting for a key
			// frame, for a stream whose packets do not say which ones are.
			static constexpr size_t kMaxSkippedPackets = 250;

			// Opens the codec context of the stream, in hardware or not.
			int OpenContext(bool hardware);

			// Gives the context a hardware device of the platform, if the codec
			// has a hardware decoder that uses one. Returns whether it did.
			bool SetUpHardware(const AVCodec * codec);

			// What libavcodec asks for to choose a pixel format: the hardware
			// one if the decoder offers it, otherwise a software one.
			static AVPixelFormat ChooseFormat(AVCodecContext * context, const AVPixelFormat * formats);

			// The next picture of the codec, in I420, hardware frames brought
			// into system memory first.
			int ReceiveFromCodec(AVFrame ** frame, int64_t * timestamp_us);

			// Whether a result of the hardware decoder means that it failed.
			// A stream that cannot be decoded, which is how corrupt data shows,
			// fails a software decoder as well and says nothing of the
			// hardware, except before its first picture, where the software
			// decoder is the one to tell.
			bool IsHardwareFailure(int result) const;

			// Opens a software decoder in place of the hardware one, which
			// failed with the given error, or if that is 0 did not take the
			// stream, and decodes the packets kept since the start again.
			// The packet being sent when it happened, if any, is the current
			// one. Returns 0, or the error that kept a decoder from opening.
			int FallBackToSoftware(int error, const AVPacket * current);

			// Hands a packet, or null to drain, to the codec, taking its
			// pictures aside while it asks for that.
			void SendToSoftware(const AVPacket * packet);

			// Whether a packet is passed over because the decoder is waiting
			// for a key frame, which it is after it lost pictures it needs.
			bool SkipUntilKeyFrame(const AVPacket * packet);

			void KeepPacket(const AVPacket * packet);
			void DropKeptPackets();
			void DropPending();

			// Converts a decoded frame to I420. The returned frame is a new
			// reference the caller owns.
			int ConvertToI420(const AVFrame * source, AVFrame ** result);

			// A new I420 frame of the given size, whose buffer comes from a
			// pool, so that the picture lands in memory that is already
			// mapped. The caller owns the frame.
			int AllocateI420(int width, int height, AVFrame ** result);

			// A picture decoded while the packets of a failed hardware decoder
			// were decoded again, which the caller has not asked for yet.
			struct Pending
			{
				AVFrame * frame;
				int64_t timestamp_us;
			};

			const AVStream * stream_ = nullptr;
			AVCodecContext * codec_context_ = nullptr;
			SwsContext * sws_context_ = nullptr;
			AVFrame * decoded_ = nullptr;
			AVFrame * transferred_ = nullptr;
			// The size of the decoder's surfaces that transferred_ was made for.
			int transfer_width_ = 0;
			int transfer_height_ = 0;
			AVBufferPool * pool_ = nullptr;
			int pool_width_ = 0;
			int pool_height_ = 0;
			AVRational time_base_ = { 0, 1 };

			AVPixelFormat hardware_format_ = AV_PIX_FMT_NONE;
			std::atomic<bool> hardware_{ false };

			// Until the hardware decoder has produced a frame, the packets
			// sent to it are kept.
			bool probation_ = false;
			std::vector<AVPacket *> kept_;

			// Set by ChooseFormat when the decoder went for a software format.
			bool software_selected_ = false;

			// Whether the end of the stream was announced, which a decoder that
			// takes over has to be told as well.
			bool draining_ = false;

			// After a software decoder took over from a hardware one that had
			// delivered pictures, it has none of the ones the next refer to,
			// and starts again at a key frame.
			bool awaiting_key_frame_ = false;
			size_t skipped_ = 0;
			std::deque<Pending> pending_;
	};
}

#endif
