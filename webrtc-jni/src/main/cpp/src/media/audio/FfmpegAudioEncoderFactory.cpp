/*
 *  Copyright (c) 2019 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#include "media/audio/FfmpegAudioEncoderFactory.h"
#include "media/audio/FfmpegAudioEncoder.h"

#include <memory>
#include <vector>
#include <algorithm>
#include <audio/FfmpegAudioDecoder.h>

#include "api/audio_codecs/audio_encoder_factory_template.h"
#include "rtc_base/logging.h"

extern "C" {
#include <libavcodec/avcodec.h>
}

namespace webrtc
{
    // FFmpeg 코덱 이름 목록 가져오기
    namespace
    {
        std::vector<std::string> GetAvailableCodecs()
        {
            std::vector<std::string> codec_names;

            // FFmpeg에서 지원하는 모든 오디오 인코더 순회
            void* opaque = nullptr;
            const AVCodec* codec = nullptr;
            while ((codec = av_codec_iterate(&opaque)))
            {
                if (av_codec_is_encoder(codec) && codec->type == AVMEDIA_TYPE_AUDIO)
                {
                    codec_names.push_back(codec->name);
                }
            }

            return codec_names;
        }
    }

    // FFmpeg 오디오 인코더 팩토리 클래스
    class FfmpegAudioEncoderFactory : public AudioEncoderFactory
    {
    public:
        explicit FfmpegAudioEncoderFactory(const std::vector<std::string>& codec_names = {})
        {
            // 지정된 코덱만 사용하거나, 지정되지 않으면 모든 코덱 사용
            if (codec_names.empty())
            {
                supported_codecs_ = GetAvailableCodecs();
            }
            else
            {
                supported_codecs_ = codec_names;
            }

            RTC_LOG(LS_INFO) << "FFmpeg audio encoder factory created with "
                << supported_codecs_.size() << " codecs";
            for (const auto& name : supported_codecs_)
            {
                RTC_LOG(LS_INFO) << "Supported FFmpeg audio codec: " << name;
            }
        }

        std::vector<AudioCodecSpec> GetSupportedEncoders() override
        {
            std::vector<AudioCodecSpec> specs;
            AudioEncoderFfmpeg::AppendSupportedEncoders(&specs);

            // 지원하는 코덱만 필터링
            if (!supported_codecs_.empty())
            {
                auto it = specs.begin();
                while (it != specs.end())
                {
                    std::string name = it->format.name;
                    std::transform(name.begin(), name.end(), name.begin(),
                                   [](unsigned char c) { return std::tolower(c); });

                    bool supported = false;
                    for (const auto& codec : supported_codecs_)
                    {
                        if (name == codec ||
                            (name == "opus" && (codec == "libopus" || codec == "opus")) ||
                            (name == "libopus" && (codec == "opus" || codec == "libopus")) ||
                            (name == "vorbis" && (codec == "libvorbis" || codec == "vorbis")) ||
                            (name == "libvorbis" && (codec == "vorbis" || codec == "libvorbis")))
                        {
                            supported = true;
                            break;
                        }
                    }

                    if (supported)
                    {
                        ++it;
                    }
                    else
                    {
                        it = specs.erase(it);
                    }
                }
            }

            return specs;
        }

        absl::optional<AudioCodecInfo> QueryAudioEncoder(
            const SdpAudioFormat& format) override
        {
            auto config = AudioEncoderFfmpeg::SdpToConfig(format);
            if (!config)
            {
                return absl::nullopt;
            }

            // 지원하는 코덱인지 확인
            if (!supported_codecs_.empty())
            {
                std::string name = config->codec_name;
                bool supported = false;
                for (const auto& codec : supported_codecs_)
                {
                    if (name == codec ||
                        (name == "libopus" && codec == "opus") ||
                        (name == "libvorbis" && codec == "vorbis"))
                    {
                        supported = true;
                        break;
                    }
                }

                if (!supported)
                {
                    return absl::nullopt;
                }
            }

            return AudioEncoderFfmpeg::QueryAudioEncoder(*config);
        }

        std::unique_ptr<AudioEncoder> MakeAudioEncoder(
            int payload_type,
            const SdpAudioFormat& format,
            absl::optional<AudioCodecPairId> codec_pair_id) override
        {
            auto config = AudioEncoderFfmpeg::SdpToConfig(format);
            if (!config)
            {
                return nullptr;
            }

            // 지원하는 코덱인지 확인
            if (!supported_codecs_.empty())
            {
                std::string name = config->codec_name;
                bool supported = false;
                for (const auto& codec : supported_codecs_)
                {
                    if (name == codec ||
                        (name == "libopus" && codec == "opus") ||
                        (name == "libvorbis" && codec == "vorbis"))
                    {
                        supported = true;
                        break;
                    }
                }

                if (!supported)
                {
                    return nullptr;
                }
            }

            return AudioEncoderFfmpeg::MakeAudioEncoder(*config, payload_type, codec_pair_id);
        }

    private:
        std::vector<std::string> supported_codecs_;
    };

    // 사용 가능한 FFmpeg 오디오 인코더 목록 가져오기
    std::vector<std::string> GetSupportedFfmpegAudioEncoders()
    {
        return GetAvailableCodecs();
    }

    // 모든 FFmpeg 오디오 인코더를 지원하는 팩토리 생성
    rtc::scoped_refptr<AudioEncoderFactory> CreateFfmpegAudioEncoderFactory()
    {
        return rtc::make_ref_counted<FfmpegAudioEncoderFactory>();
    }

    // 특정 코덱만 지원하는 FFmpeg 오디오 인코더 팩토리 생성
    rtc::scoped_refptr<AudioEncoderFactory> CreateFfmpegAudioEncoderFactory(
        const std::vector<std::string>& codec_names)
    {
        return rtc::make_ref_counted<FfmpegAudioEncoderFactory>(codec_names);
    }
} // namespace webrtc
