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

		private:
			const AVStream * VideoStream() const;
			const AVStream * AudioStream() const;

			AVFormatContext * format_context_ = nullptr;
			int video_stream_index_ = -1;
			int audio_stream_index_ = -1;
	};
}

#endif
