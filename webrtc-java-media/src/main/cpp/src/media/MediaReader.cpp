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

#include "media/MediaReader.h"

extern "C" {
#include <libavutil/avutil.h>
#include <libavcodec/avcodec.h>
}

namespace ffmpeg
{
	MediaReader::~MediaReader()
	{
		Close();
	}

	int MediaReader::Open(const std::string & url)
	{
		Close();

		int result = avformat_open_input(&format_context_, url.c_str(), nullptr, nullptr);

		if (result < 0) {
			// avformat_open_input frees the context and nulls the pointer
			// itself when it fails, so there is nothing left to release.
			format_context_ = nullptr;

			return result;
		}

		result = avformat_find_stream_info(format_context_, nullptr);

		if (result < 0) {
			Close();

			return result;
		}

		// A container may hold several streams of a kind, and libavformat is
		// the one that knows which of them is meant to be played. A source
		// with no stream of a kind reports AVERROR_STREAM_NOT_FOUND, which is
		// not a failure: a file may be video only or audio only.
		video_stream_index_ = av_find_best_stream(format_context_,
				AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
		audio_stream_index_ = av_find_best_stream(format_context_,
				AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);

		if (video_stream_index_ < 0) {
			video_stream_index_ = -1;
		}
		if (audio_stream_index_ < 0) {
			audio_stream_index_ = -1;
		}

		if (video_stream_index_ < 0 && audio_stream_index_ < 0) {
			// Nothing here can be played, which is worth reporting as a
			// failure rather than handing back an empty reader.
			Close();

			return AVERROR_STREAM_NOT_FOUND;
		}

		return 0;
	}

	void MediaReader::Close()
	{
		if (format_context_ != nullptr) {
			avformat_close_input(&format_context_);
		}

		format_context_ = nullptr;
		video_stream_index_ = -1;
		audio_stream_index_ = -1;
	}

	bool MediaReader::IsOpen() const
	{
		return format_context_ != nullptr;
	}

	int64_t MediaReader::GetDurationUs() const
	{
		if (format_context_ == nullptr || format_context_->duration == AV_NOPTS_VALUE) {
			return 0;
		}

		// AVFormatContext::duration is already in AV_TIME_BASE units, which
		// are microseconds.
		return format_context_->duration;
	}

	bool MediaReader::HasVideo() const
	{
		return VideoStream() != nullptr;
	}

	int MediaReader::GetVideoWidth() const
	{
		const AVStream * stream = VideoStream();

		return stream != nullptr ? stream->codecpar->width : 0;
	}

	int MediaReader::GetVideoHeight() const
	{
		const AVStream * stream = VideoStream();

		return stream != nullptr ? stream->codecpar->height : 0;
	}

	double MediaReader::GetFrameRate() const
	{
		const AVStream * stream = VideoStream();

		if (stream == nullptr || stream->avg_frame_rate.den == 0) {
			return 0;
		}

		return av_q2d(stream->avg_frame_rate);
	}

	const char * MediaReader::GetVideoCodecName() const
	{
		const AVStream * stream = VideoStream();

		return avcodec_get_name(stream != nullptr
				? stream->codecpar->codec_id : AV_CODEC_ID_NONE);
	}

	bool MediaReader::HasAudio() const
	{
		return AudioStream() != nullptr;
	}

	int MediaReader::GetSampleRate() const
	{
		const AVStream * stream = AudioStream();

		return stream != nullptr ? stream->codecpar->sample_rate : 0;
	}

	int MediaReader::GetChannels() const
	{
		const AVStream * stream = AudioStream();

		return stream != nullptr ? stream->codecpar->ch_layout.nb_channels : 0;
	}

	const char * MediaReader::GetAudioCodecName() const
	{
		const AVStream * stream = AudioStream();

		return avcodec_get_name(stream != nullptr
				? stream->codecpar->codec_id : AV_CODEC_ID_NONE);
	}

	const AVStream * MediaReader::VideoStream() const
	{
		if (format_context_ == nullptr || video_stream_index_ < 0) {
			return nullptr;
		}

		return format_context_->streams[video_stream_index_];
	}

	const AVStream * MediaReader::AudioStream() const
	{
		if (format_context_ == nullptr || audio_stream_index_ < 0) {
			return nullptr;
		}

		return format_context_->streams[audio_stream_index_];
	}
}
