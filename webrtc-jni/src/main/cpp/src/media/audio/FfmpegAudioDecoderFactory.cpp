/*
 *  Copyright (c) 2019 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#include "media/audio/FfmpegAudioDecoderFactory.h"
#include "media/audio/FfmpegAudioDecoder.h"

#include <memory>
#include <vector>
#include <algorithm>
#include <audio/FfmpegAudioEncoder.h>

#include "api/scoped_refptr.h"
#include "rtc_base/ref_counted_object.h"

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

            // FFmpeg에서 지원하는 모든 오디오 디코더 순회
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

    // FFmpeg 오디오 디코더 팩토리 클래스
    class FfmpegAudioDecoderFactory : public AudioDecoderFactory
    {
    public:
        explicit FfmpegAudioDecoderFactory(const std::vector<std::string>& codec_names = {})
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

            RTC_LOG(LS_INFO) << "FFmpeg audio decoder factory created with "
                << supported_codecs_.size() << " codecs";
            for (const auto& name : supported_codecs_)
            {
                RTC_LOG(LS_INFO) << "Supported FFmpeg audio codec: " << name;
            }
        }

        std::vector<AudioCodecSpec> GetSupportedDecoders() override
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
                            (name == "opus" && codec == "libopus") ||
                            (name == "vorbis" && codec == "libvorbis"))
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

        bool IsSupportedDecoder(const SdpAudioFormat& format) override
        {
            // SdpAudioFormat에서 코덱 이름 추출
            std::string name = format.name;
            std::transform(name.begin(), name.end(), name.begin(),
                           [](unsigned char c) { return std::tolower(c); });

            // 지원되는 모든 코덱 가져오기
            auto supported_codecs = GetAvailableCodecs();

            // 지원되는 코덱 목록에서 해당 코덱 찾기
            for (const auto& codec : supported_codecs_)
            {
                if (name == codec ||
                    (name == "opus" && codec == "libopus") ||
                    (name == "libopus" && codec == "opus") ||
                    (name == "vorbis" && codec == "libvorbis") ||
                    (name == "libvorbis" && codec == "vorbis"))
                {
                    return true;
                }
            }

            return false;
        }


        std::unique_ptr<AudioDecoder> MakeAudioDecoder(
            const SdpAudioFormat& format,
            absl::optional<AudioCodecPairId> codec_pair_id) override
        {
            auto config = AudioDecoderFfmpeg::SdpToConfig(format);
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

            return AudioDecoderFfmpeg::MakeAudioDecoder(*config);
        }

    private:
        std::vector<std::string> supported_codecs_;
    };

    // 사용 가능한 FFmpeg 오디오 디코더 목록 가져오기
    std::vector<std::string> GetSupportedFfmpegAudioDecoders()
    {
        return GetAvailableCodecs();
    }

    // 모든 FFmpeg 오디오 디코더를 지원하는 팩토리 생성
    rtc::scoped_refptr<AudioDecoderFactory> CreateFfmpegAudioDecoderFactory()
    {
        return rtc::make_ref_counted<FfmpegAudioDecoderFactory>();
    }

    // 특정 코덱만 지원하는 FFmpeg 오디오 디코더 팩토리 생성
    rtc::scoped_refptr<AudioDecoderFactory> CreateFfmpegAudioDecoderFactory(
        const std::vector<std::string>& codec_names)
    {
        return rtc::make_ref_counted<FfmpegAudioDecoderFactory>(codec_names);
    }
} // namespace webrtc
