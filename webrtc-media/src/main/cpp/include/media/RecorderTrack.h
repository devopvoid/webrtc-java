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

#ifndef WEBRTC_JAVA_MEDIA_RECORDER_TRACK_H_
#define WEBRTC_JAVA_MEDIA_RECORDER_TRACK_H_

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
}

namespace ffmpeg
{
	struct PacketDeleter
	{
		void operator()(AVPacket * packet) const
		{
			av_packet_free(&packet);
		}
	};

	using PacketPtr = std::unique_ptr<AVPacket, PacketDeleter>;

	// One encoded frame on its way into the file: the payload, copied out of
	// WebRTC, and what the recorder needs to know about it.
	struct RecordedFrame
	{
		int track = 0;
		PacketPtr packet;
		std::string mime_type;
		uint32_t ssrc = 0;
		uint32_t rtp_timestamp = 0;
		// When the frame passed the sender or receiver, in microseconds of
		// the webrtc-java clock.
		int64_t time_us = 0;
		bool key_frame = false;
		int width = 0;
		int height = 0;
	};

	// One recorded sender or receiver, as a stream in the output file.
	//
	// Nothing about the stream is known in advance: the codec, the frame size
	// and the parameter sets all come from the first frames. A track is
	// therefore pending until a frame tells it enough to describe its stream,
	// which for video takes a key frame.
	//
	// Used on the recorder's writer thread only.
	class RecorderTrack
	{
		public:
			enum class Status
			{
				// Still waiting for a frame that describes the stream.
				kPending,
				// Knows enough to describe its stream.
				kReady,
				// Cannot be recorded into this file at all.
				kUnsupported
			};

			explicit RecorderTrack(int index);
			~RecorderTrack() = default;

			RecorderTrack(const RecorderTrack &) = delete;
			RecorderTrack & operator=(const RecorderTrack &) = delete;

			int GetIndex() const;
			Status GetStatus() const;
			bool IsVideo() const;

			// Learns what it can about the stream from the frame. When this
			// makes the track unsupported, the message says why.
			Status Probe(const RecordedFrame & frame, const AVOutputFormat * format,
					std::string * message);

			// Adds the stream of a ready track to the output.
			int CreateStream(AVFormatContext * context);

			// The stream in the output, or null while there is none.
			AVStream * GetStream() const;

			// Turns the frame into a packet of this track's stream, stamped
			// with its presentation time. start_us is when recording started,
			// in the clock of RecordedFrame::time_us.
			AVPacket * Stamp(RecordedFrame & frame, int64_t start_us);

		private:
			bool DescribeVideo(const RecordedFrame & frame);
			bool DescribeAudio(const RecordedFrame & frame);

			// Takes the parameter sets out of a key frame.
			bool ExtractExtradata(const AVPacket * packet);

			// Reads the frame size out of the bitstream of a key frame, for
			// when WebRTC did not say.
			bool ParseDimensions(const AVPacket * packet);

			const int index_;
			Status status_ = Status::kPending;

			AVMediaType media_type_ = AVMEDIA_TYPE_UNKNOWN;
			AVCodecID codec_id_ = AV_CODEC_ID_NONE;
			int clock_rate_ = 0;
			int width_ = 0;
			int height_ = 0;
			int channels_ = 0;
			std::vector<uint8_t> extradata_;

			AVStream * stream_ = nullptr;

			// Timing. RTP timestamps are exact within a stream, but start at a
			// random value, so a track is anchored on the clock once and
			// follows its RTP timestamps from there.
			bool anchored_ = false;
			uint32_t ssrc_ = 0;
			uint32_t last_rtp_ = 0;
			int64_t rtp_offset_ = 0;
			int64_t base_pts_ = 0;
			int64_t last_pts_ = -1;
	};
}

#endif
