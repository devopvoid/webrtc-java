#include "media/audio/FfmpegAudioEncoder.h"
#include "rtc_base/logging.h"
namespace webrtc
{
    namespace
    {
        std::vector<std::string> GetSupportedCodecs()
        {
            std::vector<std::string> codec_names;

            // FFmpeg에서 지원하는 모든 인코더 순회
            void *opaque = nullptr;
            const AVCodec *codec = nullptr;

            while ((codec = av_codec_iterate(&opaque)))
            {
                if (av_codec_is_encoder(codec) && codec->type == AVMEDIA_TYPE_AUDIO)
                {
                    codec_names.push_back(codec->name);
                }
            }

            return codec_names;
        }

        // SDP 포맷에서 코덱 이름 추출
        std::string GetCodecNameFromSdp(const SdpAudioFormat &format)
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
                           { return std::tolower(c); });
            return name;
        }

        // 코덱 이름으로 FFmpeg 코덱 찾기
        const AVCodec *FindCodecByName(const std::string &codec_name)
        {
            return avcodec_find_encoder_by_name(codec_name.c_str());
        }

        // 샘플 포맷 선택
        AVSampleFormat SelectSampleFormat(const AVCodec *codec)
        {
            if (!codec->sample_fmts)
            {
                return AV_SAMPLE_FMT_S16;
            }

            // 지원하는 샘플 포맷 중에서 선택
            const enum AVSampleFormat *p = codec->sample_fmts;
            while (*p != AV_SAMPLE_FMT_NONE)
            {
                // 16비트 정수 포맷 우선 선택 (WebRTC와 호환성)
                if (*p == AV_SAMPLE_FMT_S16 || *p == AV_SAMPLE_FMT_S16P)
                {
                    return *p;
                }
                p++;
            }

            // 첫 번째 지원 포맷 반환
            return codec->sample_fmts[0];
        }

        // 샘플 레이트 선택
        int SelectSampleRate(const AVCodec *codec, int preferred_rate)
        {
            if (!codec->supported_samplerates)
            {
                return preferred_rate;
            }

            // 지원하는 샘플 레이트 중에서 선택
            const int *p = codec->supported_samplerates;
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

        // 채널 레이아웃 선택
        const AVChannelLayout SelectChannelLayout(const AVCodec *codec, int num_channels)
        {
            AVChannelLayout layout;
            if (!codec->ch_layouts)
            {
                // 기본 채널 레이아웃 선택
                switch (num_channels)
                {
                case 1:
                    av_channel_layout_from_mask(&layout, AV_CH_LAYOUT_MONO);
                    return layout;
                case 2:
                    av_channel_layout_from_mask(&layout, AV_CH_LAYOUT_STEREO);
                    return layout;
                default:
                    av_channel_layout_from_mask(&layout, AV_CH_LAYOUT_STEREO);
                    return layout;
                }
            }

            // 지원하는 채널 레이아웃 중에서 선택
            auto *p = codec->ch_layouts;

            while (p)
            {
                
                int layout_channels = p->nb_channels;
                if (layout_channels == num_channels)
                {
                    return *p;
                }
                p++;
            }

            // 가장 가까운 채널 수를 가진 레이아웃 선택
            p = codec->ch_layouts;
            AVChannelLayout best_layout;
            int min_diff = INT_MAX;

            while (p)
            {
                int layout_channels = p->nb_channels;
                int diff = abs(num_channels - layout_channels);
                if (diff < min_diff)
                {
                    min_diff = diff;
                    best_layout = *p;
                }
                p++;
            }

            return best_layout;
        }
    }

    // AudioEncoderFfmpeg 구현
    absl::optional<AudioEncoderFfmpegConfig> AudioEncoderFfmpeg::SdpToConfig(
        const SdpAudioFormat &audio_format)
    {

        AudioEncoderFfmpegConfig config;
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

        // 비트레이트 설정
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
        const AVCodec *codec = FindCodecByName(config.codec_name);
        if (!codec)
        {
            RTC_LOG(LS_ERROR) << "FFmpeg codec not found: " << config.codec_name;
            return absl::nullopt;
        }

        return config;
    }

    void AudioEncoderFfmpeg::AppendSupportedEncoders(
        std::vector<AudioCodecSpec> *specs)
    {

        // 지원하는 모든 FFmpeg 코덱 추가
        auto codec_names = GetSupportedCodecs();
        for (const auto &name : codec_names)
        {
            const AVCodec *codec = FindCodecByName(name);
            if (!codec)
                continue;

            // SDP 포맷 이름 (대문자로 변환)
            std::string sdp_name = name;
            std::transform(sdp_name.begin(), sdp_name.end(), sdp_name.begin(),
                           [](unsigned char c)
                           { return std::toupper(c); });
            // 기본 샘플 레이트와 채널 수 설정
            int sample_rate = codec->supported_samplerates ? codec->supported_samplerates[0] : 48000;
            int num_channels = codec->ch_layouts ? codec->ch_layouts->nb_channels : 2;

            SdpAudioFormat format(sdp_name, sample_rate, num_channels);
            format.parameters["rate"] = std::to_string(sample_rate);
            format.parameters["channels"] = std::to_string(num_channels);

            auto config = SdpToConfig(format);
            if (config) {
                AudioCodecSpec spec = {
                    format,
                    QueryAudioEncoder(*config)};
                specs->push_back(spec);
            }
        }
    }

    AudioCodecInfo AudioEncoderFfmpeg::QueryAudioEncoder(
        const AudioEncoderFfmpegConfig &config)
    {

        const AVCodec *codec = FindCodecByName(config.codec_name);
        if (!codec)
        {
            return {48000, 1, 64000}; // 기본값
        }

        int sample_rate = SelectSampleRate(codec, config.sample_rate_hz);
        AVChannelLayout channel_layout = SelectChannelLayout(codec, config.num_channels);
        
        int num_channels = channel_layout.nb_channels;

        return {sample_rate, (size_t)num_channels, config.bitrate_bps};
    }

    std::unique_ptr<AudioEncoder> AudioEncoderFfmpeg::MakeAudioEncoder(
        const AudioEncoderFfmpegConfig &config,
        int payload_type,
        absl::optional<AudioCodecPairId> /*codec_pair_id*/)
    {

        if (!config.IsOk())
        {
            RTC_LOG(LS_ERROR) << "Invalid FFmpeg encoder config";
            return nullptr;
        }

        return std::make_unique<AudioEncoderFfmpegImpl>(config, payload_type);
    }

    // AudioEncoderFfmpegImpl 구현
    AudioEncoderFfmpegImpl::AudioEncoderFfmpegImpl(
        const AudioEncoderFfmpegConfig &config, int payload_type)
        : config_(config),
          payload_type_(payload_type),
          target_bitrate_bps_(config.bitrate_bps),
          codec_(nullptr),
          codec_context_(nullptr),
          av_frame_(nullptr),
          av_packet_(nullptr),
          first_timestamp_in_buffer_(0),
          num_10ms_frames_per_packet_(config.frame_size_ms / 10),
          num_10ms_frames_buffered_(0),
          encoder_initialized_(false)
    {

        // 입력 버퍼 초기화
        const size_t samples_per_10ms = config.sample_rate_hz / 100;
        input_buffer_.reserve(num_10ms_frames_per_packet_ * samples_per_10ms * config.num_channels);

        // 인코더 초기화
        InitializeEncoder();
    }

    AudioEncoderFfmpegImpl::~AudioEncoderFfmpegImpl()
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

    bool AudioEncoderFfmpegImpl::InitializeEncoder()
    {
        // 이미 초기화되었으면 성공 반환
        if (encoder_initialized_)
        {
            return true;
        }

        // FFmpeg 코덱 찾기
        codec_ = FindCodecByName(config_.codec_name);
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
        codec_context_->bit_rate = config_.bitrate_bps;
        codec_context_->sample_fmt = SelectSampleFormat(codec_);
        codec_context_->sample_rate = SelectSampleRate(codec_, config_.sample_rate_hz);
        codec_context_->ch_layout = SelectChannelLayout(codec_, config_.num_channels);

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

        // 프레임 설정
        av_frame_->format = codec_context_->sample_fmt;
        av_frame_->ch_layout.nb_channels = codec_context_->ch_layout.nb_channels;
        av_frame_->sample_rate = codec_context_->sample_rate;
        av_frame_->nb_samples = codec_context_->frame_size;

        // 프레임 버퍼 할당
        ret = av_frame_get_buffer(av_frame_, 0);
        if (ret < 0)
        {
            char error_buf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, error_buf, AV_ERROR_MAX_STRING_SIZE);
            RTC_LOG(LS_ERROR) << "Failed to allocate frame buffer: " << error_buf;
            return false;
        }

        // 패킷 할당
        av_packet_ = av_packet_alloc();
        if (!av_packet_)
        {
            RTC_LOG(LS_ERROR) << "Failed to allocate packet";
            return false;
        }

        encoder_initialized_ = true;
        return true;
    }

    int AudioEncoderFfmpegImpl::SampleRateHz() const
    {
        return config_.sample_rate_hz;
    }

    size_t AudioEncoderFfmpegImpl::NumChannels() const
    {
        return config_.num_channels;
    }

    int AudioEncoderFfmpegImpl::RtpTimestampRateHz() const
    {
        return config_.sample_rate_hz;
    }

    size_t AudioEncoderFfmpegImpl::Num10MsFramesInNextPacket() const
    {
        return num_10ms_frames_per_packet_;
    }

    size_t AudioEncoderFfmpegImpl::Max10MsFramesInAPacket() const
    {
        return num_10ms_frames_per_packet_;
    }

    int AudioEncoderFfmpegImpl::GetTargetBitrate() const
    {
        return target_bitrate_bps_;
    }

    void AudioEncoderFfmpegImpl::Reset()
    {
        // 버퍼 초기화
        input_buffer_.clear();
        num_10ms_frames_buffered_ = 0;

        // 인코더 재설정이 필요하면 여기서 처리
        if (codec_context_)
        {
            avcodec_flush_buffers(codec_context_);
        }
    }

    absl::optional<std::pair<TimeDelta, TimeDelta>> AudioEncoderFfmpegImpl::GetFrameLengthRange() const
    {
        const int min_frame_ms = 10;
        const int max_frame_ms = config_.frame_size_ms;
        return std::pair<TimeDelta, TimeDelta>(TimeDelta::Millis(min_frame_ms),
                                               TimeDelta::Millis(max_frame_ms));
    }

    AudioEncoder::EncodedInfo AudioEncoderFfmpegImpl::EncodeImpl(
        uint32_t rtp_timestamp,
        rtc::ArrayView<const int16_t> audio,
        rtc::Buffer *encoded)
    {

        // 인코더가 초기화되지 않았으면 초기화
        if (!encoder_initialized_ && !InitializeEncoder())
        {
            // 초기화 실패 시 빈 결과 반환
            EncodedInfo info;
            info.encoded_bytes = 0;
            info.encoded_timestamp = rtp_timestamp;
            info.payload_type = payload_type_;
            return info;
        }

        // 첫 프레임이면 타임스탬프 저장
        if (num_10ms_frames_buffered_ == 0)
        {
            first_timestamp_in_buffer_ = rtp_timestamp;
        }

        // 오디오 데이터를 입력 버퍼에 추가
        input_buffer_.insert(input_buffer_.end(), audio.begin(), audio.end());
        num_10ms_frames_buffered_++;

        // 패킷을 구성하기에 충분한 프레임이 모이지 않았으면 인코딩하지 않음
        if (num_10ms_frames_buffered_ < num_10ms_frames_per_packet_)
        {
            EncodedInfo info;
            info.encoded_bytes = 0;
            info.encoded_timestamp = first_timestamp_in_buffer_;
            info.payload_type = payload_type_;
            return info;
        }

        // 프레임 데이터 설정
        int ret = av_frame_make_writable(av_frame_);
        if (ret < 0)
        {
            RTC_LOG(LS_ERROR) << "Failed to make frame writable";
            Reset();
            EncodedInfo info;
            info.encoded_bytes = 0;
            info.encoded_timestamp = first_timestamp_in_buffer_;
            info.payload_type = payload_type_;
            return info;
        }

        // 샘플 포맷에 따라 데이터 복사 방식 결정
        if (codec_context_->sample_fmt == AV_SAMPLE_FMT_S16)
        {
            // 인터리브드 포맷 (S16)
            memcpy(av_frame_->data[0], input_buffer_.data(),
                   input_buffer_.size() * sizeof(int16_t));
        }
        else if (codec_context_->sample_fmt == AV_SAMPLE_FMT_S16P)
        {
            // 플래너 포맷 (S16P)
            int samples_per_channel = input_buffer_.size() / config_.num_channels;
            for (int ch = 0; ch < config_.num_channels; ch++)
            {
                int16_t *dst = reinterpret_cast<int16_t *>(av_frame_->data[ch]);
                for (int i = 0; i < samples_per_channel; i++)
                {
                    dst[i] = input_buffer_[i * config_.num_channels + ch];
                }
            }
        }
        else
        {
            // 다른 포맷은 현재 지원하지 않음
            RTC_LOG(LS_ERROR) << "Unsupported sample format: " << codec_context_->sample_fmt;
            Reset();
            EncodedInfo info;
            info.encoded_bytes = 0;
            info.encoded_timestamp = first_timestamp_in_buffer_;
            info.payload_type = payload_type_;
            return info;
        }

        // 프레임 인코딩
        ret = avcodec_send_frame(codec_context_, av_frame_);
        if (ret < 0)
        {
            char error_buf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, error_buf, AV_ERROR_MAX_STRING_SIZE);
            RTC_LOG(LS_ERROR) << "Error sending frame for encoding: " << error_buf;
            Reset();
            EncodedInfo info;
            info.encoded_bytes = 0;
            info.encoded_timestamp = first_timestamp_in_buffer_;
            info.payload_type = payload_type_;
            return info;
        }

        // 인코딩된 패킷 받기
        EncodedInfo info;
        info.encoded_bytes = 0;
        info.encoded_timestamp = first_timestamp_in_buffer_;
        info.payload_type = payload_type_;
        info.send_even_if_empty = true;
        info.speech = true;

        while (ret >= 0)
        {
            ret = avcodec_receive_packet(codec_context_, av_packet_);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
            {
                break;
            }
            else if (ret < 0)
            {
                char error_buf[AV_ERROR_MAX_STRING_SIZE];
                av_strerror(ret, error_buf, AV_ERROR_MAX_STRING_SIZE);
                RTC_LOG(LS_ERROR) << "Error receiving packet: " << error_buf;
                break;
            }

            // 인코딩된 데이터를 출력 버퍼에 추가
            encoded->AppendData(av_packet_->data, av_packet_->size);
            info.encoded_bytes += av_packet_->size;

            // 패킷 해제
            av_packet_unref(av_packet_);
        }

        // 버퍼 초기화
        input_buffer_.clear();
        num_10ms_frames_buffered_ = 0;

        return info;
    }

} // namespace webrtc
