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

#ifndef WEBRTC_JAVA_MEDIA_AUDIO_DECODER_H_
#define WEBRTC_JAVA_MEDIA_AUDIO_DECODER_H_

#include <cstdint>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
}

namespace ffmpeg
{
	// Decodes one audio stream into the only shape WebRTC accepts: interleaved
	// signed 16-bit PCM, at most 48 kHz, mono or stereo, in chunks of exactly
	// 10 ms.
	//
	// A source rarely arrives in that shape, so decoded audio goes through
	// swresample and is then re-cut into 10 ms chunks, which is why this holds
	// a buffer between calls: one decoded frame is seldom a whole number of
	// chunks.
	class AudioDecoder
	{
		public:
			// The rate WebRTC works at internally, so resampling to it here
			// saves it from doing the same work again later.
			static constexpr int kSampleRate = 48000;

			// WebRTC takes 10 ms of audio at a time and nothing else.
			static constexpr int kFramesPerChunk = kSampleRate / 100;

			AudioDecoder() = default;
			~AudioDecoder();

			AudioDecoder(const AudioDecoder &) = delete;
			AudioDecoder & operator=(const AudioDecoder &) = delete;

			// Opens a decoder for the given stream, which must outlive this
			// decoder. Source audio of more than two channels is downmixed to
			// stereo. Returns 0 or a negative AVERROR.
			int Open(const AVStream * stream);

			void Close();

			// Drops the decoder's state and the partial chunk being gathered,
			// which is what a seek needs.
			void Flush();

			// Hands a packet to the decoder, or null to start draining at the
			// end of the stream. Returns 0 or a negative AVERROR.
			int SendPacket(const AVPacket * packet);

			// Takes the next 10 ms of audio, resampled and interleaved.
			//
			// Returns 0 when a chunk was produced, AVERROR(EAGAIN) when the
			// decoder needs another packet first, AVERROR_EOF once draining
			// has finished, or another negative AVERROR. The tail of a stream
			// that does not fill a whole chunk is padded with silence, since a
			// short chunk is not something WebRTC can be given.
			int ReceiveChunk(std::vector<int16_t> & chunk, int64_t * timestamp_us);

			int GetChannels() const;

		private:
			// Resamples one decoded frame into the pending buffer.
			int Resample(const AVFrame * frame);

			// Moves whatever swresample still holds into the pending buffer,
			// which it only hands out when told that no more input follows.
			int DrainResampler();

			// Sets swresample up to convert from the given input to the
			// output this decoder produces, replacing any earlier setup.
			int ConfigureResampler(const AVChannelLayout * layout, int format,
					int sample_rate);

			// Moves one chunk out of the pending buffer.
			void TakeChunk(std::vector<int16_t> & chunk, int64_t * timestamp_us);

			AVCodecContext * codec_context_ = nullptr;
			SwrContext * swr_context_ = nullptr;
			AVFrame * decoded_ = nullptr;

			// The input swresample is set up for. A stream may change any of
			// these from one frame to the next, and a frame that no longer
			// matches would be read with the wrong layout.
			AVChannelLayout in_layout_ = {};
			int in_format_ = -1;
			int in_rate_ = 0;
			AVRational time_base_ = { 0, 1 };
			int channels_ = 0;

			// Resampled samples not yet handed out, interleaved.
			std::vector<int16_t> pending_;

			// When the next chunk starts, in microseconds on the source's own
			// timeline. Chunks advance it in exact 10 ms steps, so the audio
			// this produces is perfectly regular whatever the container's
			// packet timing looks like.
			int64_t next_timestamp_us_ = 0;
			bool have_timestamp_ = false;

			// Set once the resampler has been drained at the end of the
			// stream, so that it is drained once and not on every call.
			bool resampler_drained_ = false;
	};
}

#endif
