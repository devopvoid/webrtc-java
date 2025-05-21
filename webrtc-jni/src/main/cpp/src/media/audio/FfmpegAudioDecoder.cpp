#include "media/audio/FfmpegAudioDecoder.h"
#include "rtc_base/logging.h"

namespace webrtc
{
    namespace
    {
        std::vector<std::string> GetSupportedDecoderCodecs()
        {
            std::vector<std::string> codec_names;

            // FFmpeg에서 지원하는 모든 디코더 순회
            void* opaque = nullptr;
            const AVCodec* codec = nullptr;

            while ((codec = av_codec_iterate(&opaque)))
            {
                if (av_codec_is_decoder(codec) && codec->type == AVMEDIA_TYPE_AUDIO)
                {
                    codec_names.push_back(codec->name);
                }
            }

            return codec_names;
        }

        // SDP 포맷에서 코덱 이름 추출
        std::string GetCodecNameFromSdp(const SdpAudioFormat& format)
        {
            if (format.name == "AAC")
                return "aac";
            if (format.name == "MP3")
                return "mp3";
            if (format.name == "OPUS")
                return "libopus";
            if (format.name == "VORBIS")
                return "libvorbis";
            if (format.name == "FLAC")
                return "flac";

            // 소문자로 변환하여 반환
            std::string name = format.name;
            std::transform(name.begin(), name.end(), name.begin(),
                           [](unsigned char c)
                           {
                               return std::tolower(c);
                           });
            return name;
        }

        // 코덱 이름으로 FFmpeg 코덱 찾기
        const AVCodec* FindDecoderCodecByName(const std::string& codec_name)
        {
            return avcodec_find_decoder_by_name(codec_name.c_str());
        }

        // 샘플 포맷 선택
        AVSampleFormat SelectSampleFormat(const AVCodec* codec)
        {
            if (!codec->sample_fmts)
            {
                return AV_SAMPLE_FMT_S16;
            }

            // 지원하는 샘플 포맷 중에서 선택
            const enum AVSampleFormat* p = codec->sample_fmts;

            // 우선순위에 따라 샘플 포맷 선택
            // 1. 16비트 정수 포맷 (WebRTC와 호환성)
            while (*p != AV_SAMPLE_FMT_NONE)
            {
                if (*p == AV_SAMPLE_FMT_S16 || *p == AV_SAMPLE_FMT_S16P)
                {
                    return *p;
                }
                p++;
            }

            // 2. 32비트 정수 포맷
            p = codec->sample_fmts;
            while (*p != AV_SAMPLE_FMT_NONE)
            {
                if (*p == AV_SAMPLE_FMT_S32 || *p == AV_SAMPLE_FMT_S32P)
                {
                    return *p;
                }
                p++;
            }

            // 3. 32비트 부동소수점 포맷
            p = codec->sample_fmts;
            while (*p != AV_SAMPLE_FMT_NONE)
            {
                if (*p == AV_SAMPLE_FMT_FLT || *p == AV_SAMPLE_FMT_FLTP)
                {
                    return *p;
                }
                p++;
            }

            // 4. 64비트 부동소수점 포맷
            p = codec->sample_fmts;
            while (*p != AV_SAMPLE_FMT_NONE)
            {
                if (*p == AV_SAMPLE_FMT_DBL || *p == AV_SAMPLE_FMT_DBLP)
                {
                    return *p;
                }
                p++;
            }

            // 첫 번째 지원 포맷 반환
            return codec->sample_fmts[0];
        }

        // 샘플 레이트 선택
        int SelectSampleRate(const AVCodec* codec, int preferred_rate)
        {
            if (!codec->supported_samplerates)
            {
                return preferred_rate;
            }

            // 지원하는 샘플 레이트 중에서 선택
            const int* p = codec->supported_samplerates;
            int best_rate = 0;
            int min_diff = INT_MAX;

            while (*p)
            {
                int diff = abs(preferred_rate - *p);
                if (diff < min_diff)
                {
                    min_diff = diff;
                    best_rate = *p;
                }
                p++;
            }

            return best_rate > 0 ? best_rate : preferred_rate;
        }

        // This function has been removed to ensure consistent channel handling between encoder and decoder

        // 채널 레이아웃 선택
        const AVChannelLayout* SelectChannelLayout(const AVCodec* codec, int num_channels, AVChannelLayout* out)
        {
            if (!codec->ch_layouts)
            {
                // 기본 채널 레이아웃 선택
                switch (num_channels)
                {
                case 1:
                    av_channel_layout_from_mask(out, AV_CH_LAYOUT_MONO);
                    return out;
                case 2:
                    av_channel_layout_from_mask(out, AV_CH_LAYOUT_STEREO);
                    return out;
                default:
                    av_channel_layout_from_mask(out, AV_CH_LAYOUT_STEREO);
                    return out;
                }
            }

            // 지원하는 채널 레이아웃 중에서 선택
            auto p = (AVChannelLayout*)codec->ch_layouts;

            while (p && p->nb_channels)
            {
                int layout_channels = p->nb_channels;
                if (layout_channels == num_channels)
                {
                    av_channel_layout_copy(out, p);
                    return p;
                }
                p++;
            }

            // 가장 가까운 채널 수를 가진 레이아웃 선택
            p = (AVChannelLayout*)codec->ch_layouts;
            AVChannelLayout* best_layout;
            int min_diff = INT_MAX;

            while (p && p->nb_channels)
            {
                int layout_channels = p->nb_channels;
                int diff = abs(num_channels - layout_channels);
                if (diff < min_diff)
                {
                    min_diff = diff;
                    best_layout = p;
                }
                p++;
            }

            av_channel_layout_copy(out, best_layout);
            return best_layout;
        }

        AudioCodecInfo QueryAudioDecoder(
            const AudioDecoderFfmpegConfig& config)
        {
            const AVCodec* codec = FindDecoderCodecByName(config.codec_name);
            if (!codec)
            {
                return {48000, 1, 64000}; // 기본값
            }

            int sample_rate = SelectSampleRate(codec, config.sample_rate_hz);
            AVChannelLayout channel_layout;
            SelectChannelLayout(codec, config.num_channels, &channel_layout);
            int num_channels = channel_layout.nb_channels;

            return {sample_rate, static_cast<size_t>(num_channels), config.bitrate_bps};
        }
    }

    // AudioDecoderFfmpeg 구현
    absl::optional<AudioDecoderFfmpegConfig> AudioDecoderFfmpeg::SdpToConfig(
        const SdpAudioFormat& audio_format)
    {
        AudioDecoderFfmpegConfig config;
        config.codec_name = GetCodecNameFromSdp(audio_format);

        // 샘플 레이트 설정
        auto it = audio_format.parameters.find("rate");
        if (it != audio_format.parameters.end())
        {
            config.sample_rate_hz = std::stoi(it->second);
        }
        else
        {
            config.sample_rate_hz = 48000; // 기본값
        }

        // 채널 수 설정
        it = audio_format.parameters.find("channels");
        if (it != audio_format.parameters.end())
        {
            config.num_channels = std::stoi(it->second);
        }
        else
        {
            config.num_channels = audio_format.num_channels;
        }

        it = audio_format.parameters.find("bitrate");
        if (it != audio_format.parameters.end())
        {
            config.bitrate_bps = std::stoi(it->second);
        }
        else
        {
            config.bitrate_bps = 64000; // 기본값
        }

        // 프레임 크기 설정
        it = audio_format.parameters.find("frame_size_ms");
        if (it != audio_format.parameters.end())
        {
            config.frame_size_ms = std::stoi(it->second);
        }
        else
        {
            config.frame_size_ms = 20; // 기본값
        }

        // 코덱이 존재하는지 확인
        const AVCodec* codec = FindDecoderCodecByName(config.codec_name);
        if (!codec)
        {
            RTC_LOG(LS_ERROR) << "FFmpeg codec not found: " << config.codec_name;
            return absl::nullopt;
        }

        return config;
    }


    void AudioDecoderFfmpeg::AppendSupportedDecoders(
        std::vector<AudioCodecSpec>* specs)
    {
        // 지원하는 모든 FFmpeg 코덱 추가
        auto codec_names = GetSupportedDecoderCodecs();
        for (const auto& name : codec_names)
        {
            const AVCodec* codec = FindDecoderCodecByName(name);
            if (!codec)
                continue;

            // SDP 포맷 이름 (대문자로 변환)
            std::string sdp_name = name;
            std::transform(sdp_name.begin(), sdp_name.end(), sdp_name.begin(),
                           [](unsigned char c)
                           {
                               return std::toupper(c);
                           });
            // 기본 샘플 레이트와 채널 수 설정
            int sample_rate = codec->supported_samplerates ? codec->supported_samplerates[0] : 48000;

            // 채널 레이아웃 선택 (2채널 기본값)
            AVChannelLayout ch_layout;
            SelectChannelLayout(codec, 2, &ch_layout);
            int num_channels = ch_layout.nb_channels;

            SdpAudioFormat format(sdp_name, sample_rate, num_channels);
            format.parameters["rate"] = std::to_string(sample_rate);
            format.parameters["channels"] = std::to_string(num_channels);

            auto config = SdpToConfig(format);
            if (config)
            {
                AudioCodecSpec spec = {format, QueryAudioDecoder(*config)};
                specs->push_back(spec);
            }
        }
    }

    std::unique_ptr<AudioDecoder> AudioDecoderFfmpeg::MakeAudioDecoder(
        const AudioDecoderFfmpegConfig& config)
    {
        if (!config.IsOk())
        {
            RTC_LOG(LS_ERROR) << "Invalid FFmpeg decoder config";
            return nullptr;
        }

        return std::make_unique<AudioDecoderFfmpegImpl>(config);
    }

    // AudioDecoderFfmpegImpl 구현
    AudioDecoderFfmpegImpl::AudioDecoderFfmpegImpl(
        const AudioDecoderFfmpegConfig& config)
        : config_(config),
          codec_(nullptr),
          codec_context_(nullptr),
          av_frame_(nullptr),
          av_packet_(nullptr),
          decoder_initialized_(false),
          output_sample_rate_hz_(config.sample_rate_hz),
          output_channels_(config.num_channels)
    {
        // 디코더 초기화
        InitializeDecoder();
    }

    AudioDecoderFfmpegImpl::~AudioDecoderFfmpegImpl()
    {
        // 리소스 해제
        if (av_frame_)
        {
            av_frame_free(&av_frame_);
        }

        if (av_packet_)
        {
            av_packet_free(&av_packet_);
        }

        if (codec_context_)
        {
            avcodec_free_context(&codec_context_);
        }
    }

    bool AudioDecoderFfmpegImpl::InitializeDecoder()
    {
        av_log_set_level(AV_LOG_DEBUG);
        // 이미 초기화되었으면 성공 반환
        if (decoder_initialized_)
        {
            return true;
        }

        // FFmpeg 코덱 찾기
        codec_ = FindDecoderCodecByName(config_.codec_name);
        if (!codec_)
        {
            RTC_LOG(LS_ERROR) << "FFmpeg codec not found: " << config_.codec_name;
            return false;
        }

        // 코덱 컨텍스트 할당
        codec_context_ = avcodec_alloc_context3(codec_);
        if (!codec_context_)
        {
            RTC_LOG(LS_ERROR) << "Failed to allocate codec context";
            return false;
        }

        // 코덱 파라미터 설정
        codec_context_->sample_fmt = SelectSampleFormat(codec_);
        codec_context_->sample_rate = SelectSampleRate(codec_, config_.sample_rate_hz);
        SelectChannelLayout(codec_, config_.num_channels, &codec_context_->ch_layout);
        // 코덱 열기
        int ret = avcodec_open2(codec_context_, codec_, nullptr);
        if (ret < 0)
        {
            char error_buf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, error_buf, AV_ERROR_MAX_STRING_SIZE);
            RTC_LOG(LS_ERROR) << "Failed to open codec: " << error_buf;
            return false;
        }

        // 프레임 할당
        av_frame_ = av_frame_alloc();
        if (!av_frame_)
        {
            RTC_LOG(LS_ERROR) << "Failed to allocate audio frame";
            return false;
        }

        // 패킷 할당
        av_packet_ = av_packet_alloc();
        if (!av_packet_)
        {
            RTC_LOG(LS_ERROR) << "Failed to allocate packet";
            return false;
        }

        // 출력 파라미터 설정
        output_sample_rate_hz_ = codec_context_->sample_rate;
        output_channels_ = codec_context_->ch_layout.nb_channels;

        decoder_initialized_ = true;
        return true;
    }

    int AudioDecoderFfmpegImpl::SampleRateHz() const
    {
        return output_sample_rate_hz_;
    }

    size_t AudioDecoderFfmpegImpl::Channels() const
    {
        return output_channels_;
    }

    void AudioDecoderFfmpegImpl::Reset()
    {
        // 디코더 재설정이 필요하면 여기서 처리
        if (codec_context_)
        {
            avcodec_flush_buffers(codec_context_);
        }
    }

    bool AudioDecoderFfmpegImpl::HasDecodePlc() const
    {
        // FFmpeg 디코더는 PLC(Packet Loss Concealment)를 지원하지 않음
        return false;
    }

    int AudioDecoderFfmpegImpl::PacketDuration(const uint8_t* encoded, size_t encoded_len) const
    {
        // 패킷 길이를 기반으로 한 예상 지속 시간 (샘플 단위)
        // 정확한 계산은 코덱마다 다를 수 있음
        return 0; // 알 수 없음을 의미
    }

    int AudioDecoderFfmpegImpl::DecodeInternal(const uint8_t* encoded,
                                               size_t encoded_len,
                                               int sample_rate_hz,
                                               int16_t* decoded,
                                               SpeechType* speech_type)
    {
        // 인코더가 초기화되지 않았으면 초기화
        if (!decoder_initialized_ && !InitializeDecoder())
        {
            // 초기화 실패 시 오류 반환
            return -1;
        }

        // 패킷 데이터 설정
        av_packet_unref(av_packet_);
        av_packet_->data = const_cast<uint8_t*>(encoded);
        av_packet_->size = encoded_len;

        // 패킷 전송
        int ret = avcodec_send_packet(codec_context_, av_packet_);
        if (ret < 0)
        {
            char error_buf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, error_buf, AV_ERROR_MAX_STRING_SIZE);
            RTC_LOG(LS_ERROR) << "Error sending packet for decoding: " << error_buf;
            return -1;
        }

        // 프레임 수신
        ret = avcodec_receive_frame(codec_context_, av_frame_);
        if (ret < 0)
        {
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
            {
                // 더 많은 데이터가 필요하거나 스트림 끝
                return 0;
            }
            char error_buf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, error_buf, AV_ERROR_MAX_STRING_SIZE);
            RTC_LOG(LS_ERROR) << "Error receiving frame: " << error_buf;
            return -1;
        }

        // 디코딩된 샘플 수
        int num_samples = av_frame_->nb_samples;
        if (num_samples <= 0)
        {
            return 0;
        }

        // 필요한 변수 초기화
        int16_t* output_buffer = decoded;
        int output_size = num_samples * output_channels_ * sizeof(int16_t);

        // 샘플 포맷에 따라 데이터 처리
        switch (codec_context_->sample_fmt)
        {
        case AV_SAMPLE_FMT_S16: // 16-bit signed integer (interleaved)
            memcpy(output_buffer, av_frame_->data[0], output_size);
            break;

        case AV_SAMPLE_FMT_S16P:
            {
                // 16-bit signed integer (planar)
                for (int ch = 0; ch < output_channels_; ch++)
                {
                    auto src = reinterpret_cast<const int16_t*>(av_frame_->extended_data[ch]);
                    for (int i = 0; i < num_samples; i++)
                    {
                        output_buffer[i * output_channels_ + ch] = src[i];
                    }
                }
                break;
            }

        case AV_SAMPLE_FMT_S32: // 32-bit signed integer (interleaved)
        case AV_SAMPLE_FMT_FLT: // 32-bit float (interleaved)
        case AV_SAMPLE_FMT_DBL:
            {
                // 64-bit float (interleaved)
                // Convert to S16 interleaved
                const int num_channels = av_frame_->ch_layout.nb_channels;
                const int sample_size = av_get_bytes_per_sample(codec_context_->sample_fmt);
                const uint8_t* src = av_frame_->data[0];

                for (int i = 0; i < num_samples; i++)
                {
                    for (int ch = 0; ch < num_channels; ch++)
                    {
                        int32_t sample;

                        // Read sample based on format
                        if (codec_context_->sample_fmt == AV_SAMPLE_FMT_S32)
                        {
                            sample = *reinterpret_cast<const int32_t*>(src + (i * num_channels + ch) * sample_size) >>
                                16;
                        }
                        else if (codec_context_->sample_fmt == AV_SAMPLE_FMT_FLT)
                        {
                            float f = *reinterpret_cast<const float*>(src + (i * num_channels + ch) * sample_size);
                            // Clamp to [-1.0, 1.0] and scale to 16-bit
                            f = std::max(-1.0f, std::min(1.0f, f));
                            sample = static_cast<int32_t>(f * 32767.0f);
                        }
                        else
                        {
                            // AV_SAMPLE_FMT_DBL
                            double d = *reinterpret_cast<const double*>(src + (i * num_channels + ch) * sample_size);
                            // Clamp to [-1.0, 1.0] and scale to 16-bit
                            d = std::max(-1.0, std::min(1.0, d));
                            sample = static_cast<int32_t>(d * 32767.0);
                        }

                        // Clamp to 16-bit range
                        output_buffer[i * output_channels_ + ch] =
                            static_cast<int16_t>(std::max(-32768, std::min(32767, sample)));
                    }
                }
                break;
            }

        case AV_SAMPLE_FMT_S32P: // 32-bit signed integer (planar)
        case AV_SAMPLE_FMT_FLTP: // 32-bit float (planar)
        case AV_SAMPLE_FMT_DBLP:
            {
                // 64-bit float (planar)
                // Convert from planar to interleaved S16
                const int num_channels = av_frame_->ch_layout.nb_channels;

                for (int i = 0; i < num_samples; i++)
                {
                    for (int ch = 0; ch < num_channels; ch++)
                    {
                        int32_t sample;
                        const uint8_t* src = av_frame_->extended_data[ch] + i * av_get_bytes_per_sample(
                            codec_context_->sample_fmt);

                        // Read sample based on format
                        if (codec_context_->sample_fmt == AV_SAMPLE_FMT_S32P)
                        {
                            sample = *reinterpret_cast<const int32_t*>(src) >> 16;
                        }
                        else if (codec_context_->sample_fmt == AV_SAMPLE_FMT_FLTP)
                        {
                            float f = *reinterpret_cast<const float*>(src);
                            // Clamp to [-1.0, 1.0] and scale to 16-bit
                            f = std::max(-1.0f, std::min(1.0f, f));
                            sample = static_cast<int32_t>(f * 32767.0f);
                        }
                        else
                        {
                            // AV_SAMPLE_FMT_DBLP
                            double d = *reinterpret_cast<const double*>(src);
                            // Clamp to [-1.0, 1.0] and scale to 16-bit
                            d = std::max(-1.0, std::min(1.0, d));
                            sample = static_cast<int32_t>(d * 32767.0);
                        }

                        // Clamp to 16-bit range
                        output_buffer[i * output_channels_ + ch] =
                            static_cast<int16_t>(std::max(-32768, std::min(32767, sample)));
                    }
                }
                break;
            }

        default:
            // 지원하지 않는 포맷
            RTC_LOG(LS_ERROR) << "Unsupported sample format: " << av_get_sample_fmt_name(codec_context_->sample_fmt);
            return -1;
        }

        // 음성 타입 설정 (일반 음성으로 가정)
        *speech_type = kSpeech;

        // 디코딩된 샘플 수 반환
        return num_samples;
    }
} // namespace webrtc
