/*
 *  Copyright (c) 2017 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#ifndef API_AUDIO_CODECS_FFMPEG_AUDIO_ENCODER_FFMPEG_H_
#define API_AUDIO_CODECS_FFMPEG_AUDIO_ENCODER_FFMPEG_H_

#include <memory>
#include <vector>
#include <string>
#include <rtc_base/constructor_magic.h>

#include "absl/types/optional.h"
#include "api/audio_codecs/audio_codec_pair_id.h"
#include "api/audio_codecs/audio_encoder.h"
#include "api/audio_codecs/audio_format.h"
#include "modules/audio_coding/audio_network_adaptor/include/audio_network_adaptor.h"

#include "rtc_base/system/rtc_export.h"

extern "C"
{
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

namespace webrtc
{

    // FFmpeg 오디오 인코더 구성 클래스
    struct RTC_EXPORT AudioEncoderFfmpegConfig
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

    struct RTC_EXPORT AudioEncoderFfmpeg
    {
        static absl::optional<AudioEncoderFfmpegConfig> SdpToConfig(
            const SdpAudioFormat &audio_format);
        static void AppendSupportedEncoders(std::vector<AudioCodecSpec> *specs);
        static AudioCodecInfo QueryAudioEncoder(const AudioEncoderFfmpegConfig &config);
        static std::unique_ptr<AudioEncoder> MakeAudioEncoder(
            const AudioEncoderFfmpegConfig &config,
            int payload_type,
            absl::optional<AudioCodecPairId> codec_pair_id = absl::nullopt);
    };

    class AudioEncoderFfmpegImpl : public webrtc::AudioEncoder
    {
    public:
        AudioEncoderFfmpegImpl(const AudioEncoderFfmpegConfig &config, int payload_type);
        ~AudioEncoderFfmpegImpl() override;

        // AudioEncoder 인터페이스 구현
        int SampleRateHz() const override;
        size_t NumChannels() const override;
        int RtpTimestampRateHz() const override;
        size_t Num10MsFramesInNextPacket() const override;
        size_t Max10MsFramesInAPacket() const override;
        int GetTargetBitrate() const override;
        void Reset() override;
        absl::optional<std::pair<TimeDelta, TimeDelta>> GetFrameLengthRange() const override;

    protected:
        EncodedInfo EncodeImpl(uint32_t rtp_timestamp,
                               rtc::ArrayView<const int16_t> audio,
                               rtc::Buffer *encoded) override;

    private:
        bool InitializeEncoder();

        // 구성 정보
        AudioEncoderFfmpegConfig config_;
        int payload_type_;
        int target_bitrate_bps_;

        // FFmpeg 관련 객체
        const AVCodec *codec_;
        AVCodecContext *codec_context_;
        AVFrame *av_frame_;
        AVPacket *av_packet_;

        // 버퍼링 관련
        std::vector<int16_t> input_buffer_;
        uint32_t first_timestamp_in_buffer_;
        size_t num_10ms_frames_per_packet_;
        size_t num_10ms_frames_buffered_;
        bool encoder_initialized_;

        RTC_DISALLOW_COPY_AND_ASSIGN(AudioEncoderFfmpegImpl);
    };

} // namespace webrtc

#endif // API_AUDIO_CODECS_FFMPEG_AUDIO_ENCODER_FFMPEG_H_
