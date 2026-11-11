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

#include "media/RecorderOutput.h"
#include "media/WriteFault.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <utility>

extern "C" {
#include <libavutil/dict.h>
#include <libavutil/mathematics.h>
}

namespace ffmpeg
{
	namespace
	{
		bool IsMp4(const AVOutputFormat * format)
		{
			return std::strcmp(format->name, "mp4") == 0 || std::strcmp(format->name, "mov") == 0;
		}
	}

	RecorderOutput::RecorderOutput(std::string path, int64_t start_us, int64_t index) :
		path_(std::move(path)),
		start_us_(start_us),
		index_(index)
	{
	}

	RecorderOutput::~RecorderOutput()
	{
		if (context_ != nullptr) {
			if (context_->pb != nullptr && !(context_->oformat->flags & AVFMT_NOFILE)) {
				avio_closep(&context_->pb);
			}

			avformat_free_context(context_);
		}
	}

	int RecorderOutput::Open(bool exclusive)
	{
		int result = avformat_alloc_output_context2(&context_, nullptr, nullptr, path_.c_str());

		if (result < 0 || context_ == nullptr) {
			result = avformat_alloc_output_context2(&context_, nullptr, "matroska", path_.c_str());
		}
		if (result < 0) {
			return result;
		}

		// Frames arrive in real time, so the streams never drift far apart,
		// and holding back more for interleaving only costs memory.
		context_->max_interleave_delta = 2000000;

		if (context_->oformat->flags & AVFMT_NOFILE) {
			return 0;
		}

		// FFmpeg opens a file for writing by emptying it, so a file that
		// exists is looked for first. Something else creating it in between
		// is not guarded against.
		if (exclusive && avio_check(path_.c_str(), 0) >= 0) {
			return AVERROR(EEXIST);
		}

		return WriteFault::Check(WriteFault::Call::kOpen,
				avio_open(&context_->pb, path_.c_str(), AVIO_FLAG_WRITE));
	}

	const std::string & RecorderOutput::GetPath() const
	{
		return path_;
	}

	int64_t RecorderOutput::GetIndex() const
	{
		return index_;
	}

	int64_t RecorderOutput::GetStartUs() const
	{
		return start_us_;
	}

	const AVOutputFormat * RecorderOutput::GetFormat() const
	{
		return context_ != nullptr ? context_->oformat : nullptr;
	}

	int RecorderOutput::AddStream(const RecorderTrack & track)
	{
		const size_t index = static_cast<size_t>(track.GetIndex());

		if (streams_.size() <= index) {
			streams_.resize(index + 1);
		}

		Stream & stream = streams_[index];
		int result = track.CreateStream(context_, &stream.stream);

		if (result < 0) {
			stream.stream = nullptr;
			return result;
		}

		stream.clock_rate = track.GetClockRate();

		return 0;
	}

	bool RecorderOutput::HasStream(int track) const
	{
		return track >= 0 && static_cast<size_t>(track) < streams_.size()
				&& streams_[track].stream != nullptr;
	}

	bool RecorderOutput::HasStreams() const
	{
		return std::any_of(streams_.begin(), streams_.end(),
				[](const Stream & stream) { return stream.stream != nullptr; });
	}

	int RecorderOutput::WriteHeader()
	{
		AVDictionary * options = nullptr;

		if (IsMp4(context_->oformat)) {
			// Fragmented, so that a recording cut short, by a crash or a full
			// disk, still plays up to where it stopped; and in fragments of a
			// second rather than per key frame, which WebRTC sends rarely and
			// which would hold whole minutes in memory.
			av_dict_set(&options, "movflags", "+empty_moov+default_base_moof", 0);
			av_dict_set(&options, "frag_duration", "1000000", 0);
		}

		int result = WriteFault::Check(WriteFault::Call::kHeader, avformat_write_header(context_, &options));

		av_dict_free(&options);

		if (result < 0) {
			return result;
		}

		header_written_ = true;

		if (context_->pb != nullptr) {
			bytes_ = avio_tell(context_->pb);
		}

		return 0;
	}

	bool RecorderOutput::IsHeaderWritten() const
	{
		return header_written_;
	}

	int RecorderOutput::Write(RecordedFrame & frame, bool keep)
	{
		Stream & stream = streams_[frame.track];
		AVPacket * packet = frame.packet.get();
		PacketPtr reference;

		if (keep) {
			// Shares the payload, which a buffer holds on to.
			reference = PacketPtr(av_packet_alloc());

			if (reference == nullptr || av_packet_ref(reference.get(), packet) < 0) {
				return AVERROR(ENOMEM);
			}

			packet = reference.get();
		}

		const AVRational track_time_base{ 1, stream.clock_rate };
		const int64_t start_pts = av_rescale(start_us_, stream.clock_rate, 1000000);

		// Every container wants increasing timestamps, and some want them
		// strictly increasing even where frames share an RTP timestamp. A
		// frame of one track that arrived a little before the frame that
		// began the file is moved to its start.
		int64_t pts = av_rescale_q(std::max<int64_t>(frame.pts - start_pts, 0),
				track_time_base, stream.stream->time_base);

		if (pts <= stream.last_pts) {
			pts = stream.last_pts + 1;
		}

		stream.last_pts = pts;

		packet->pts = pts;
		packet->dts = pts;
		packet->stream_index = stream.stream->index;
		packet->time_base = stream.stream->time_base;

		if (frame.key_frame || stream.stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
			packet->flags |= AV_PKT_FLAG_KEY;
		}

		const int size = packet->size;

		// Takes the packet's reference and leaves the packet blank.
		int result = WriteFault::Check(WriteFault::Call::kPacket, av_interleaved_write_frame(context_, packet));

		if (result < 0) {
			return result;
		}

		packets_++;
		bytes_ += size;
		last_us_ = std::max(last_us_, frame.timeline_us);

		return 0;
	}

	int RecorderOutput::WriteTrailer()
	{
		if (!header_written_) {
			return 0;
		}

		return WriteFault::Check(WriteFault::Call::kTrailer, av_write_trailer(context_));
	}

	int RecorderOutput::Close()
	{
		if (context_ == nullptr || context_->pb == nullptr || (context_->oformat->flags & AVFMT_NOFILE)) {
			return 0;
		}

		// Flushes what is still buffered, so a full disk may only show here,
		// and the file system may refuse to close a file it could not keep.
		return WriteFault::Check(WriteFault::Call::kClose, avio_closep(&context_->pb));
	}

	bool RecorderOutput::HasMedia() const
	{
		return packets_ > 0;
	}

	int64_t RecorderOutput::GetDurationUs() const
	{
		return packets_ > 0 ? std::max<int64_t>(last_us_ - start_us_, 0) : 0;
	}

	int64_t RecorderOutput::GetBytes() const
	{
		return bytes_;
	}

	void RecorderOutput::SetFailed()
	{
		failed_ = true;
	}

	bool RecorderOutput::IsFailed() const
	{
		return failed_;
	}
}
