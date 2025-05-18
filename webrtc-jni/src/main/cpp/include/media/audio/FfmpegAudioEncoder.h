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
#include <third_party/ffmpeg/libavcodec/avcodec.h>
#include <rtc_base/constructor_magic.h>

#include "absl/types/optional.h"
#include "api/audio_codecs/audio_codec_pair_id.h"
#include "api/audio_codecs/audio_encoder.h"
#include "api/audio_codecs/audio_format.h"
#include "modules/audio_coding/audio_network_adaptor/include/audio_network_adaptor.h"

#include "rtc_base/system/rtc_export.h"

namespace webrtc {

    
    struct RTC_EXPORT AudioEncoderFfmpeg {
        
        static absl::optional<AVCodec> SdpToConfig(
            const SdpAudioFormat& audio_format);
        static void AppendSupportedEncoders(std::vector<AudioCodecSpec>* specs);
        static AudioCodecInfo QueryAudioEncoder(const AVCodec& codec);
        static std::unique_ptr<AudioEncoder> MakeAudioEncoder(
            const AVCodec& codec,
            int payload_type,
            absl::optional<AudioCodecPairId> codec_pair_id = absl::nullopt);
    };


    class AudioEncoderFfmpegImpl : public webrtc::AudioEncoder
    {
    public:
        AudioEncoderFfmpegImpl(const char* name);
        ~AudioEncoderFfmpegImpl() override;
        int SampleRateHz() const override;
        size_t NumChannels() const override;
        int RtpTimestampRateHz() const override;
        size_t Num10MsFramesInNextPacket() const override;
        size_t Max10MsFramesInAPacket() const override;
        int GetTargetBitrate() const override;
        void Reset() override;
        absl::optional<std::pair<TimeDelta, TimeDelta>> GetFrameLengthRange() const override;

    protected:
        EncodedInfo EncodeImpl(uint32_t rtp_timestamp, rtc::ArrayView<const int16_t> audio, rtc::Buffer* encoded) override;
    private:
        const AVCodec* codec_;
        AVCodecContext* codec_context_;
        int payload_type_;
        std::vector<int16_t> input_buffer_;
        uint32_t first_timestamp_in_buffer_;
        size_t num_10ms_frames_per_packet_;
        size_t num_10ms_frames_buffered_;
        size_t num_channels_;
        int next_frame_length_ms_;

        RTC_DISALLOW_COPY_AND_ASSIGN(AudioEncoderFfmpegImpl);
    };

}  // namespace webrtc

#endif  // API_AUDIO_CODECS_FFMPEG_AUDIO_ENCODER_FFMPEG_H_
