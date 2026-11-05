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
#include "media/AllocationFault.h"

#include <algorithm>
#include <cstring>
#include <mutex>

extern "C" {
#include <libavutil/avutil.h>
#include <libavutil/time.h>
#include <libavcodec/avcodec.h>
}

namespace
{
	// The protocols a source may use, including the ones a source opens on
	// its own behalf, such as the TCP connection behind an rtsp:// URL. The
	// build also carries HTTP, for RTSP tunnelled over it, which is left out
	// here: nothing this module plays is meant to fetch an arbitrary URL.
	constexpr const char * kProtocolWhitelist = "file,pipe,rtsp,rtp,udp,tcp";

	// How far before the first frame a seek to the start aims. A search by
	// timestamp, which is all a container without an index has, goes by decode
	// times, and those of the first keyframe come before its presentation
	// time: aiming at that exactly finds the keyframe after it.
	constexpr int64_t kStartMarginUs = 1000000;

	std::once_flag network_initialized;
}

namespace ffmpeg
{
	MediaReader::MediaReader(int64_t timeout_us)
		: timeout_us_(timeout_us)
	{
	}

	MediaReader::~MediaReader()
	{
		Close();
	}

	int MediaReader::Open(const std::string & url)
	{
		Close();

		std::call_once(network_initialized, [] {
			avformat_network_init();
		});

		// Allocated here rather than by avformat_open_input, since the
		// interrupt callback has to be in place before opening starts.
		format_context_ = avformat_alloc_context();

		if (format_context_ == nullptr) {
			return AVERROR(ENOMEM);
		}

		format_context_->interrupt_callback.callback = &MediaReader::OnInterrupt;
		format_context_->interrupt_callback.opaque = this;

		AVDictionary * options = nullptr;

		int result = AllocationFault::CheckOption(
				av_dict_set(&options, "protocol_whitelist", kProtocolWhitelist, 0));

		if (result < 0) {
			// Without the whitelist a source could reach any protocol of the
			// build, so nothing is opened.
			av_dict_free(&options);
			avformat_free_context(format_context_);
			format_context_ = nullptr;

			return result;
		}

		BeginBlocking();
		result = avformat_open_input(&format_context_, url.c_str(), nullptr, &options);
		EndBlocking();

		av_dict_free(&options);

		if (result < 0) {
			// avformat_open_input frees the context and nulls the pointer
			// itself when it fails, so there is nothing left to release.
			format_context_ = nullptr;

			return TranslateError(result);
		}

		BeginBlocking();
		result = avformat_find_stream_info(format_context_, nullptr);
		EndBlocking();

		if (result < 0) {
			result = TranslateError(result);

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

		start_time_us_ = FindStartTimeUs();

		return 0;
	}

	int64_t MediaReader::FindStartTimeUs() const
	{
		int64_t start_us = AV_NOPTS_VALUE;

		// The streams that are played, and not the container's own start: that
		// also takes in the streams nothing here plays, such as a data track.
		for (const AVStream * stream : { GetVideoStream(), GetAudioStream() }) {
			if (stream == nullptr || stream->start_time == AV_NOPTS_VALUE) {
				continue;
			}

			const int64_t stream_us = av_rescale_q(stream->start_time, stream->time_base,
					AV_TIME_BASE_Q);

			start_us = start_us == AV_NOPTS_VALUE ? stream_us : std::min(start_us, stream_us);
		}

		if (start_us == AV_NOPTS_VALUE) {
			start_us = format_context_->start_time;
		}

		return start_us != AV_NOPTS_VALUE ? start_us : 0;
	}

	void MediaReader::Close()
	{
		if (format_context_ != nullptr) {
			// Closing an RTSP source tells the server, which can block as
			// well, unless the reader was interrupted, in which case it
			// gives up at once.
			BeginBlocking();
			avformat_close_input(&format_context_);
			EndBlocking();
		}

		format_context_ = nullptr;
		video_stream_index_ = -1;
		audio_stream_index_ = -1;
		start_time_us_ = 0;
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
		const int64_t duration_us = format_context_->duration;

		// Matroska gives where the file ends on its clock, which libavformat
		// passes on, and that is only how long it runs if it starts at zero.
		if (start_time_us_ > 0 && std::strstr(format_context_->iformat->name, "matroska") != nullptr) {
			return std::max<int64_t>(duration_us - start_time_us_, 0);
		}

		return duration_us;
	}

	int64_t MediaReader::GetStartTimeUs() const
	{
		return start_time_us_;
	}

	bool MediaReader::HasVideo() const
	{
		return GetVideoStream() != nullptr;
	}

	int MediaReader::GetVideoWidth() const
	{
		const AVStream * stream = GetVideoStream();

		return stream != nullptr ? stream->codecpar->width : 0;
	}

	int MediaReader::GetVideoHeight() const
	{
		const AVStream * stream = GetVideoStream();

		return stream != nullptr ? stream->codecpar->height : 0;
	}

	double MediaReader::GetFrameRate() const
	{
		const AVStream * stream = GetVideoStream();

		if (stream == nullptr || stream->avg_frame_rate.den == 0) {
			return 0;
		}

		return av_q2d(stream->avg_frame_rate);
	}

	const char * MediaReader::GetVideoCodecName() const
	{
		const AVStream * stream = GetVideoStream();

		return avcodec_get_name(stream != nullptr
				? stream->codecpar->codec_id : AV_CODEC_ID_NONE);
	}

	bool MediaReader::HasAudio() const
	{
		return GetAudioStream() != nullptr;
	}

	int MediaReader::GetSampleRate() const
	{
		const AVStream * stream = GetAudioStream();

		return stream != nullptr ? stream->codecpar->sample_rate : 0;
	}

	int MediaReader::GetChannels() const
	{
		const AVStream * stream = GetAudioStream();

		return stream != nullptr ? stream->codecpar->ch_layout.nb_channels : 0;
	}

	const char * MediaReader::GetAudioCodecName() const
	{
		const AVStream * stream = GetAudioStream();

		return avcodec_get_name(stream != nullptr
				? stream->codecpar->codec_id : AV_CODEC_ID_NONE);
	}

	int MediaReader::GetVideoStreamIndex() const
	{
		return video_stream_index_;
	}

	int MediaReader::GetAudioStreamIndex() const
	{
		return audio_stream_index_;
	}

	int MediaReader::ReadPacket(AVPacket * packet)
	{
		if (format_context_ == nullptr) {
			return AVERROR(EINVAL);
		}

		BeginBlocking();
		int result = av_read_frame(format_context_, packet);
		EndBlocking();

		return result == AVERROR_EOF ? result : TranslateError(result);
	}

	int MediaReader::Seek(int64_t position_us)
	{
		if (format_context_ == nullptr) {
			return AVERROR(EINVAL);
		}

		if (position_us < 0) {
			position_us = 0;
		}

		// libavformat takes the time on the container's clock, which is not
		// where the source starts.
		const int64_t target_us = position_us + start_time_us_;
		const int64_t search_us = position_us > 0 ? target_us
				: std::min(start_time_us_, std::max<int64_t>(start_time_us_ - kStartMarginUs, 0));

		// libavformat searches the video by default. Before it starts there
		// is no keyframe to go to, and a demuxer such as Matroska then goes to
		// its first one and skips the audio that comes before that.
		const AVStream * video = GetVideoStream();
		const AVStream * audio = GetAudioStream();
		const bool audio_first = video != nullptr && audio != nullptr
				&& video->start_time != AV_NOPTS_VALUE
				&& target_us < av_rescale_q(video->start_time, video->time_base, AV_TIME_BASE_Q);

		// AVSEEK_FLAG_BACKWARD lands on the keyframe at or before the target,
		// so that what follows can actually be decoded. Frames between that
		// keyframe and the target are decoded and dropped by the caller.
		int result = SeekStream(audio_first ? audio : nullptr, search_us, AVSEEK_FLAG_BACKWARD);

		if (result >= 0 || interrupted_.load() || timed_out_.load()) {
			return TranslateError(result);
		}

		// Nothing at or before the target, which some demuxers take for a
		// failure. A stream that starts late has that for all that comes
		// before it, and the first keyframe after is the nearest there is.
		return TranslateError(SeekStream(audio_first ? audio : nullptr, target_us, 0));
	}

	int MediaReader::SeekStream(const AVStream * stream, int64_t time_us, int flags)
	{
		// Without a stream, libavformat picks one and takes the time as it is.
		const int index = stream != nullptr ? stream->index : -1;
		const int64_t timestamp = stream != nullptr
				? av_rescale_q(time_us, AV_TIME_BASE_Q, stream->time_base) : time_us;

		BeginBlocking();
		int result = av_seek_frame(format_context_, index, timestamp, flags);
		EndBlocking();

		return result;
	}

	void MediaReader::Interrupt()
	{
		interrupted_.store(true);
	}

	int MediaReader::OnInterrupt(void * opaque)
	{
		MediaReader * reader = static_cast<MediaReader *>(opaque);

		if (reader->interrupted_.load()) {
			return 1;
		}

		const int64_t deadline = reader->deadline_us_.load();

		if (deadline != 0 && av_gettime_relative() > deadline) {
			reader->timed_out_.store(true);

			return 1;
		}

		return 0;
	}

	void MediaReader::BeginBlocking()
	{
		timed_out_.store(false);

		if (timeout_us_ <= 0) {
			deadline_us_.store(0);
			return;
		}

		// Saturated: a timeout too long to count arrives as INT64_MAX, and the
		// sum would wrap around into a deadline that has passed already.
		const int64_t now = av_gettime_relative();

		deadline_us_.store(timeout_us_ > INT64_MAX - now ? INT64_MAX : now + timeout_us_);
	}

	void MediaReader::EndBlocking()
	{
		deadline_us_.store(0);
	}

	int MediaReader::TranslateError(int error) const
	{
		if (error < 0 && !interrupted_.load() && timed_out_.load()) {
			return AVERROR(ETIMEDOUT);
		}

		return error;
	}

	const AVStream * MediaReader::GetVideoStream() const
	{
		if (format_context_ == nullptr || video_stream_index_ < 0) {
			return nullptr;
		}

		return format_context_->streams[video_stream_index_];
	}

	const AVStream * MediaReader::GetAudioStream() const
	{
		if (format_context_ == nullptr || audio_stream_index_ < 0) {
			return nullptr;
		}

		return format_context_->streams[audio_stream_index_];
	}
}
