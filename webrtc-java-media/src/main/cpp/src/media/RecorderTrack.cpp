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

#include "media/RecorderTrack.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/channel_layout.h>
#include <libavutil/intreadwrite.h>
#include <libavutil/mathematics.h>
#include <libavutil/mem.h>
}

namespace ffmpeg
{
	namespace
	{
		// A jump this large between where the RTP timestamps and the clock
		// put a frame means the sender restarted its RTP clock.
		constexpr int64_t kMaxDriftSeconds = 10;

		struct CodecInfo
		{
			const char * mime_type;
			AVMediaType media_type;
			AVCodecID codec_id;
			int clock_rate;
		};

		// The codecs WebRTC sends that a container can hold as they are.
		constexpr CodecInfo kCodecs[] = {
			{ "video/vp8", AVMEDIA_TYPE_VIDEO, AV_CODEC_ID_VP8, 90000 },
			{ "video/vp9", AVMEDIA_TYPE_VIDEO, AV_CODEC_ID_VP9, 90000 },
			{ "video/av1", AVMEDIA_TYPE_VIDEO, AV_CODEC_ID_AV1, 90000 },
			{ "video/h264", AVMEDIA_TYPE_VIDEO, AV_CODEC_ID_H264, 90000 },
			{ "video/h265", AVMEDIA_TYPE_VIDEO, AV_CODEC_ID_HEVC, 90000 },
			{ "audio/opus", AVMEDIA_TYPE_AUDIO, AV_CODEC_ID_OPUS, 48000 },
			{ "audio/pcmu", AVMEDIA_TYPE_AUDIO, AV_CODEC_ID_PCM_MULAW, 8000 },
			{ "audio/pcma", AVMEDIA_TYPE_AUDIO, AV_CODEC_ID_PCM_ALAW, 8000 },
		};

		const CodecInfo * FindCodec(const std::string & mime_type)
		{
			std::string lower(mime_type);

			std::transform(lower.begin(), lower.end(), lower.begin(),
					[](unsigned char c) { return static_cast<char>(std::tolower(c)); });

			for (const CodecInfo & codec : kCodecs) {
				if (lower == codec.mime_type) {
					return &codec;
				}
			}

			return nullptr;
		}

		// Whether the container holds the codec. The codec tags say most of
		// it, but the MP4 family refuses some codecs only when the header is
		// written, which would fail the whole file rather than leave out one
		// track.
		bool CanHold(const AVOutputFormat * format, AVCodecID codec_id)
		{
			const bool mp4 = std::strcmp(format->name, "mp4") == 0;
			const bool mov = std::strcmp(format->name, "mov") == 0;

			if ((mp4 || mov) && codec_id == AV_CODEC_ID_VP8) {
				return false;
			}
			if (mov && (codec_id == AV_CODEC_ID_VP9 || codec_id == AV_CODEC_ID_AV1)) {
				return false;
			}

			return avformat_query_codec(format, codec_id, FF_COMPLIANCE_NORMAL) == 1;
		}

		bool NeedsExtradata(AVCodecID codec_id)
		{
			return codec_id == AV_CODEC_ID_H264 || codec_id == AV_CODEC_ID_HEVC
					|| codec_id == AV_CODEC_ID_AV1;
		}

		// Reads bits most significant first, as the VP9 headers are written.
		class BitReader
		{
			public:
				BitReader(const uint8_t * data, size_t size) :
					data_(data), size_(size)
				{
				}

				bool Read(int count, uint32_t * value)
				{
					uint32_t result = 0;

					for (int i = 0; i < count; i++) {
						if (position_ >= size_ * 8) {
							return false;
						}

						int bit = (data_[position_ / 8] >> (7 - position_ % 8)) & 1;
						result = (result << 1) | static_cast<uint32_t>(bit);
						position_++;
					}

					*value = result;

					return true;
				}

			private:
				const uint8_t * data_;
				size_t size_;
				size_t position_ = 0;
		};

		// The frame size of a VP8 key frame, from its frame header (RFC 6386,
		// section 9.1).
		bool ParseVp8Dimensions(const uint8_t * data, size_t size, int * width, int * height)
		{
			if (size < 10 || (data[0] & 0x01) != 0) {
				return false;
			}
			if (data[3] != 0x9D || data[4] != 0x01 || data[5] != 0x2A) {
				return false;
			}

			*width = AV_RL16(data + 6) & 0x3FFF;
			*height = AV_RL16(data + 8) & 0x3FFF;

			return *width > 0 && *height > 0;
		}

		// The frame size of a VP9 key frame, from its uncompressed header
		// (VP9 bitstream specification, section 6.2).
		bool ParseVp9Dimensions(const uint8_t * data, size_t size, int * width, int * height)
		{
			BitReader reader(data, size);
			uint32_t value = 0;
			uint32_t profile_low = 0;
			uint32_t profile_high = 0;

			if (!reader.Read(2, &value) || value != 2) {
				return false;
			}
			if (!reader.Read(1, &profile_low) || !reader.Read(1, &profile_high)) {
				return false;
			}

			uint32_t profile = (profile_high << 1) | profile_low;

			if (profile == 3 && !reader.Read(1, &value)) {
				return false;
			}

			// show_existing_frame, then frame_type, which is 0 for a key frame.
			if (!reader.Read(1, &value) || value != 0) {
				return false;
			}
			if (!reader.Read(1, &value) || value != 0) {
				return false;
			}

			// show_frame, error_resilient_mode, then the sync code.
			if (!reader.Read(2, &value) || !reader.Read(24, &value) || value != 0x498342) {
				return false;
			}

			// color_config()
			if (profile >= 2 && !reader.Read(1, &value)) {
				return false;
			}

			uint32_t color_space = 0;

			if (!reader.Read(3, &color_space)) {
				return false;
			}

			if (color_space != 7) {
				// color_range, and for profiles 1 and 3 the subsampling and a
				// reserved bit.
				if (!reader.Read(1, &value)) {
					return false;
				}
				if ((profile == 1 || profile == 3) && !reader.Read(3, &value)) {
					return false;
				}
			}
			else if ((profile == 1 || profile == 3) && !reader.Read(1, &value)) {
				return false;
			}

			uint32_t width_minus_one = 0;
			uint32_t height_minus_one = 0;

			if (!reader.Read(16, &width_minus_one) || !reader.Read(16, &height_minus_one)) {
				return false;
			}

			*width = static_cast<int>(width_minus_one) + 1;
			*height = static_cast<int>(height_minus_one) + 1;

			return true;
		}
	}

	RecorderTrack::RecorderTrack(int index) :
		index_(index)
	{
	}

	int RecorderTrack::GetIndex() const
	{
		return index_;
	}

	RecorderTrack::Status RecorderTrack::GetStatus() const
	{
		return status_;
	}

	bool RecorderTrack::IsVideo() const
	{
		return media_type_ == AVMEDIA_TYPE_VIDEO;
	}

	RecorderTrack::Status RecorderTrack::Probe(const RecordedFrame & frame,
			const AVOutputFormat * format, std::string * message)
	{
		if (status_ != Status::kPending) {
			return status_;
		}

		if (codec_id_ == AV_CODEC_ID_NONE) {
			const CodecInfo * codec = FindCodec(frame.mime_type);

			if (codec == nullptr) {
				*message = "Track " + std::to_string(index_) + " is " + frame.mime_type
						+ ", which cannot be recorded";
				status_ = Status::kUnsupported;

				return status_;
			}
			if (!CanHold(format, codec->codec_id)) {
				*message = "Track " + std::to_string(index_) + " is " + frame.mime_type
						+ ", which a " + format->name + " file cannot hold; Matroska (.mkv) holds every codec WebRTC sends";
				status_ = Status::kUnsupported;

				return status_;
			}

			media_type_ = codec->media_type;
			codec_id_ = codec->codec_id;
			clock_rate_ = codec->clock_rate;
		}

		bool described = media_type_ == AVMEDIA_TYPE_VIDEO
				? DescribeVideo(frame)
				: DescribeAudio(frame);

		if (described) {
			status_ = Status::kReady;
		}

		return status_;
	}

	int RecorderTrack::CreateStream(AVFormatContext * context)
	{
		AVStream * stream = avformat_new_stream(context, nullptr);

		if (stream == nullptr) {
			return AVERROR(ENOMEM);
		}

		AVCodecParameters * par = stream->codecpar;
		par->codec_type = media_type_;
		par->codec_id = codec_id_;

		if (media_type_ == AVMEDIA_TYPE_VIDEO) {
			par->width = width_;
			par->height = height_;
		}
		else {
			av_channel_layout_default(&par->ch_layout, channels_);
			par->sample_rate = clock_rate_;
		}

		if (!extradata_.empty()) {
			par->extradata = static_cast<uint8_t *>(
					av_mallocz(extradata_.size() + AV_INPUT_BUFFER_PADDING_SIZE));

			if (par->extradata == nullptr) {
				return AVERROR(ENOMEM);
			}

			std::memcpy(par->extradata, extradata_.data(), extradata_.size());
			par->extradata_size = static_cast<int>(extradata_.size());
		}

		// A hint; the muxer picks the time base it stores, which Stamp()
		// converts to.
		stream->time_base = AVRational{ 1, clock_rate_ };
		stream_ = stream;

		return 0;
	}

	AVStream * RecorderTrack::GetStream() const
	{
		return stream_;
	}

	AVPacket * RecorderTrack::Stamp(RecordedFrame & frame, int64_t start_us)
	{
		AVPacket * packet = frame.packet.get();
		const AVRational track_time_base{ 1, clock_rate_ };

		// Where the clock puts the frame, which is what a track starts from,
		// and what it falls back on if its RTP timestamps stop making sense.
		int64_t clock_pts = av_rescale(frame.time_us - start_us, clock_rate_, 1000000);

		if (!anchored_ || frame.ssrc != ssrc_) {
			// A new SSRC is a new RTP clock, e.g. after the remote side
			// restarted its sender.
			anchored_ = true;
			ssrc_ = frame.ssrc;
			last_rtp_ = frame.rtp_timestamp;
			rtp_offset_ = 0;
			base_pts_ = clock_pts;
		}
		else {
			// The signed difference unwraps the 32-bit timestamp.
			rtp_offset_ += static_cast<int32_t>(frame.rtp_timestamp - last_rtp_);
			last_rtp_ = frame.rtp_timestamp;
		}

		int64_t pts = base_pts_ + rtp_offset_;

		if (std::llabs(pts - clock_pts) > kMaxDriftSeconds * clock_rate_) {
			base_pts_ = clock_pts - rtp_offset_;
			pts = clock_pts;
		}

		// Every container wants increasing timestamps, and some want them
		// strictly increasing even where frames share an RTP timestamp.
		int64_t stream_pts = av_rescale_q(std::max<int64_t>(pts, 0), track_time_base, stream_->time_base);

		if (stream_pts <= last_pts_) {
			stream_pts = last_pts_ + 1;
		}

		last_pts_ = stream_pts;

		packet->pts = stream_pts;
		packet->dts = stream_pts;
		packet->stream_index = stream_->index;
		packet->time_base = stream_->time_base;

		if (frame.key_frame || media_type_ == AVMEDIA_TYPE_AUDIO) {
			packet->flags |= AV_PKT_FLAG_KEY;
		}

		return packet;
	}

	bool RecorderTrack::DescribeVideo(const RecordedFrame & frame)
	{
		// Only a key frame describes the stream, and the file has to start
		// with one anyway.
		if (!frame.key_frame) {
			return false;
		}

		const AVPacket * packet = frame.packet.get();

		if (NeedsExtradata(codec_id_) && !ExtractExtradata(packet)) {
			return false;
		}

		width_ = frame.width;
		height_ = frame.height;

		if (width_ <= 0 || height_ <= 0) {
			return ParseDimensions(packet);
		}

		return true;
	}

	bool RecorderTrack::DescribeAudio(const RecordedFrame & frame)
	{
		const AVPacket * packet = frame.packet.get();

		if (codec_id_ != AV_CODEC_ID_OPUS) {
			// G.711 is always mono in WebRTC.
			channels_ = 1;

			return true;
		}

		if (packet->size < 1) {
			return false;
		}

		// The stereo flag of the TOC byte (RFC 6716, section 3.1). A decoder
		// outputs whatever channel count the header asks for, whatever a
		// packet holds, so the first packet is as good as any.
		channels_ = (packet->data[0] & 0x04) != 0 ? 2 : 1;

		// The OpusHead that Ogg, Matroska and MP4 all want as the codec's
		// private data (RFC 7845, section 5.1): no pre-skip, since WebRTC
		// packets are cut at arbitrary points of a running stream anyway.
		extradata_.assign(19, 0);
		std::memcpy(extradata_.data(), "OpusHead", 8);
		extradata_[8] = 1;
		extradata_[9] = static_cast<uint8_t>(channels_);
		AV_WL32(extradata_.data() + 12, 48000);

		return true;
	}

	bool RecorderTrack::ExtractExtradata(const AVPacket * packet)
	{
		const AVBitStreamFilter * filter = av_bsf_get_by_name("extract_extradata");
		AVBSFContext * bsf = nullptr;

		if (filter == nullptr || av_bsf_alloc(filter, &bsf) < 0) {
			return false;
		}

		bsf->par_in->codec_type = AVMEDIA_TYPE_VIDEO;
		bsf->par_in->codec_id = codec_id_;

		bool found = false;

		if (av_bsf_init(bsf) >= 0) {
			// The filter takes the packet over, so it gets a reference of its
			// own rather than the frame's.
			AVPacket * copy = av_packet_clone(packet);

			if (copy != nullptr && av_bsf_send_packet(bsf, copy) >= 0) {
				while (av_bsf_receive_packet(bsf, copy) >= 0) {
					size_t size = 0;
					const uint8_t * data = av_packet_get_side_data(copy,
							AV_PKT_DATA_NEW_EXTRADATA, &size);

					if (data != nullptr && size > 0) {
						extradata_.assign(data, data + size);
						found = true;
					}

					av_packet_unref(copy);
				}
			}

			av_packet_free(&copy);
		}

		av_bsf_free(&bsf);

		return found;
	}

	bool RecorderTrack::ParseDimensions(const AVPacket * packet)
	{
		const size_t size = static_cast<size_t>(packet->size);

		if (codec_id_ == AV_CODEC_ID_VP8) {
			return ParseVp8Dimensions(packet->data, size, &width_, &height_);
		}
		if (codec_id_ == AV_CODEC_ID_VP9) {
			return ParseVp9Dimensions(packet->data, size, &width_, &height_);
		}

		AVCodecParserContext * parser = av_parser_init(codec_id_);
		AVCodecContext * context = avcodec_alloc_context3(nullptr);

		if (parser != nullptr && context != nullptr) {
			uint8_t * output = nullptr;
			int output_size = 0;

			av_parser_parse2(parser, context, &output, &output_size, packet->data, packet->size,
					AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);

			if (parser->width <= 0) {
				// A parser that waits for the start of the next frame before
				// it looks at this one gets it by being flushed.
				av_parser_parse2(parser, context, &output, &output_size, nullptr, 0,
						AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
			}

			width_ = parser->width > 0 ? parser->width : context->width;
			height_ = parser->height > 0 ? parser->height : context->height;
		}

		if (parser != nullptr) {
			av_parser_close(parser);
		}

		avcodec_free_context(&context);

		return width_ > 0 && height_ > 0;
	}
}
