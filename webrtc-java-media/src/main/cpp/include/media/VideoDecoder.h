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
#include <libswscale/swscale.h>
}

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
	class VideoDecoder
	{
		public:
			VideoDecoder() = default;
			~VideoDecoder();

			VideoDecoder(const VideoDecoder &) = delete;
			VideoDecoder & operator=(const VideoDecoder &) = delete;

			// Opens a decoder for the given stream, which must outlive this
			// decoder. Returns 0 or a negative AVERROR.
			int Open(const AVStream * stream);

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

		private:
			// Converts a decoded frame to I420. The returned frame is a new
			// reference the caller owns.
			int ConvertToI420(const AVFrame * source, AVFrame ** result);

			AVCodecContext * codec_context_ = nullptr;
			SwsContext * sws_context_ = nullptr;
			AVFrame * decoded_ = nullptr;
			AVRational time_base_ = { 0, 1 };
	};
}

#endif
