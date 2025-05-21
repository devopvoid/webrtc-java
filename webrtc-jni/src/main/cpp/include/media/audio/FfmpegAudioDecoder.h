/*
 *  Copyright (c) 2017 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#ifndef API_AUDIO_CODECS_FFMPEG_AUDIO_DECODER_FFMPEG_H_
#define API_AUDIO_CODECS_FFMPEG_AUDIO_DECODER_FFMPEG_H_

#include <memory>
#include <vector>
#include <string>
#include <rtc_base/constructor_magic.h>

#include "absl/types/optional.h"
#include "api/audio_codecs/audio_decoder.h"
#include "api/audio_codecs/audio_format.h"
#include "rtc_base/system/rtc_export.h"

extern "C"
{
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

namespace webrtc
{

    // FFmpeg 오디오 디코더 구성 클래스
    struct RTC_EXPORT AudioDecoderFfmpegConfig
    {
        std::string codec_name;
        int sample_rate_hz = 48000;
        int num_channels = 2;
        int bitrate_bps = 64000;
        int frame_size_ms = 20;
        bool IsOk() const
        {
            return !codec_name.empty() && sample_rate_hz > 0 && num_channels > 0 &&
                   bitrate_bps > 0 && frame_size_ms > 0;
        }
    };

    struct RTC_EXPORT AudioDecoderFfmpeg
    {
        static absl::optional<AudioDecoderFfmpegConfig> SdpToConfig(
            const SdpAudioFormat &audio_format);
        static void AppendSupportedDecoders(std::vector<AudioCodecSpec> *specs);
        static std::unique_ptr<AudioDecoder> MakeAudioDecoder(
            const AudioDecoderFfmpegConfig &config);
    };

    class AudioDecoderFfmpegImpl : public webrtc::AudioDecoder
    {
    public:
        explicit AudioDecoderFfmpegImpl(const AudioDecoderFfmpegConfig &config);
        ~AudioDecoderFfmpegImpl() override;

        // AudioDecoder 인터페이스 구현
        int SampleRateHz() const override;
        size_t Channels() const override;
        int DecodeInternal(const uint8_t* encoded,
                          size_t encoded_len,
                          int sample_rate_hz,
                          int16_t* decoded,
                          SpeechType* speech_type) override;
        void Reset() override;
        bool HasDecodePlc() const override;
        int PacketDuration(const uint8_t* encoded, size_t encoded_len) const override;

    private:
        bool InitializeDecoder();

        // 구성 정보
        AudioDecoderFfmpegConfig config_;

        // FFmpeg 관련 객체
        const AVCodec *codec_;
        AVCodecContext *codec_context_;
        AVFrame *av_frame_;
        AVPacket *av_packet_;

        // 디코딩 관련
        bool decoder_initialized_;
        int output_sample_rate_hz_;
        size_t output_channels_;

        RTC_DISALLOW_COPY_AND_ASSIGN(AudioDecoderFfmpegImpl);
    };

} // namespace webrtc

#endif // API_AUDIO_CODECS_FFMPEG_AUDIO_DECODER_FFMPEG_H_
