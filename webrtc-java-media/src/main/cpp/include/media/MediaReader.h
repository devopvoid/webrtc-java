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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_READER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_READER_H_

#include <string>

extern "C" {
#include <libavformat/avformat.h>
}

namespace ffmpeg
{
	// Opens a media source with libavformat and reports what it contains.
	//
	// A source is anything libavformat accepts: a file path today, and an
	// http, rtsp or rtmp URL once those protocols are enabled in the build.
	// Opening reads the container and picks the streams that will be played.
	// Decoding is built on top of this and does not belong here.
	class MediaReader
	{
		public:
			MediaReader() = default;
			~MediaReader();

			MediaReader(const MediaReader &) = delete;
			MediaReader & operator=(const MediaReader &) = delete;

			// Opens the given source and selects the video and audio stream
			// that are meant to be played. Returns 0, or the negative AVERROR
			// libavformat reported, in which case the reader stays closed.
			int Open(const std::string & url);

			// Releases the container. Does nothing on a closed reader, and
			// runs from the destructor.
			void Close();

			bool IsOpen() const;

			// How long the source runs in microseconds, or 0 when the
			// container does not say, as is the case for a live stream.
			int64_t GetDurationUs() const;

			bool HasVideo() const;
			int GetVideoWidth() const;
			int GetVideoHeight() const;

			// The average frame rate the container reports, or 0 when it does
			// not say. A variable frame rate source only has an average.
			double GetFrameRate() const;

			// The codec name as FFmpeg spells it, never null: an unknown or
			// absent codec reads as "none". It points into libavcodec's own
			// static table and outlives this reader.
			const char * GetVideoCodecName() const;

			bool HasAudio() const;
			int GetSampleRate() const;
			int GetChannels() const;
			const char * GetAudioCodecName() const;

			// The streams the decoders attach to, or null when the source has
			// none of that kind. They belong to the reader and die with it.
			const AVStream * GetVideoStream() const;
			const AVStream * GetAudioStream() const;

			int GetVideoStreamIndex() const;
			int GetAudioStreamIndex() const;

			// Reads the next packet of any stream into the given packet, which
			// the caller unrefs. Returns 0, AVERROR_EOF once the source is
			// exhausted, or another negative AVERROR.
			int ReadPacket(AVPacket * packet);

			// Moves to the keyframe at or before the given position, in
			// microseconds from the start. The decoders have to be flushed
			// afterwards, since what they hold belongs to the old position.
			int Seek(int64_t position_us);

		private:
			AVFormatContext * format_context_ = nullptr;
			int video_stream_index_ = -1;
			int audio_stream_index_ = -1;
	};
}

#endif
