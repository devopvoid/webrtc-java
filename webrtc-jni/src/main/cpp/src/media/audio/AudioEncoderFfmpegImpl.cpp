#include <libavutil/channel_layout.h>

#include "media/audio/FfmpegAudioEncoder.h"
#include <third_party/ffmpeg/libavcodec/avcodec.h>
#include "modules/audio_coding/audio_network_adaptor/audio_network_adaptor_impl.h"
#include "modules/audio_coding/audio_network_adaptor/controller_manager.h"
#include "modules/audio_coding/codecs/legacy_encoded_audio_frame.h"

namespace webrtc
{
    AudioEncoderFfmpegImpl::AudioEncoderFfmpegImpl(const char* name)
    {
        auto codec = avcodec_find_encoder_by_name(name);
        auto codec_ctx = avcodec_alloc_context3(codec);

        this->codec_ = (AVCodec*) codec;
        this->codec_context_ = codec_ctx;
        this->payload_type_ = codec->id;
    }

    AudioEncoderFfmpegImpl::~AudioEncoderFfmpegImpl()
    {
        avcodec_free_context(&codec_context_);
    }

    int AudioEncoderFfmpegImpl::SampleRateHz() const
    {
        return codec_context_->framerate.num;
    }

    size_t AudioEncoderFfmpegImpl::NumChannels() const
    {
        return (size_t) codec_context_->channels;
    }

    int AudioEncoderFfmpegImpl::RtpTimestampRateHz() const
    {
        return 0;
    }

    size_t AudioEncoderFfmpegImpl::Num10MsFramesInNextPacket() const
    {
        return 0;
    }
    size_t AudioEncoderFfmpegImpl::Max10MsFramesInAPacket() const
    {
        return 0;
    }

    int AudioEncoderFfmpegImpl::GetTargetBitrate() const
    {
        return 0;
    }

    void AudioEncoderFfmpegImpl::Reset()
    {
        
    }

   
    absl::optional<std::pair<TimeDelta, TimeDelta>> AudioEncoderFfmpegImpl::GetFrameLengthRange() const
    {
        
    }

    static int select_sample_rate(const AVCodec *codec)
    {
        const int *p;
        int best_samplerate = 0;
  
        if (!codec->supported_samplerates)
            return 44100;
  
        p = codec->supported_samplerates;
        while (*p) {
            if (!best_samplerate || abs(44100 - *p) < abs(44100 - best_samplerate))
                best_samplerate = *p;
            p++;
        }
        return best_samplerate;
    }

    static int select_channel_layout(const AVCodec *codec)
    {
        const uint64_t *p;
        uint64_t best_ch_layout = 0;
        int best_nb_channels   = 0;
  
        if (!codec->channel_layouts)
            return AV_CH_LAYOUT_STEREO;
  
        p = codec->channel_layouts;
        while (*p) {
            int nb_channels = av_get_channel_layout_nb_channels(*p);
  
            if (nb_channels > best_nb_channels) {
                best_ch_layout    = *p;
                best_nb_channels = nb_channels;
            }
            p++;
        }
        return best_ch_layout;
    }
    
    AudioEncoderFfmpegImpl::EncodedInfo AudioEncoderFfmpegImpl::EncodeImpl(uint32_t rtp_timestamp, rtc::ArrayView<const int16_t> audio, rtc::Buffer* encoded)
    {
        if (num_10ms_frames_buffered_ == 0)
            first_timestamp_in_buffer_ = rtp_timestamp;
        
        AVFrame* frame;
        AVPacket *pkt;

        codec_context_->bit_rate = GetTargetBitrate();
        codec_context_->sample_fmt = AV_SAMPLE_FMT_S64;


        codec_context_->sample_rate = select_sample_rate(codec_);
        codec_context_->channel_layout = select_channel_layout(codec_);
        codec_context_->channels = av_get_channel_layout_nb_channels(codec_context_->channel_layout);
        
        if (avcodec_open2( codec_context_, codec_, nullptr) < 0)
        {
            return EncodedInfo();
        }

        pkt = av_packet_alloc();
        frame = av_frame_alloc();

        frame->nb_samples = codec_context_->frame_size;
        frame->format = codec_context_->sample_fmt;
        frame->channel_layout = codec_context_->channel_layout;

        int ret = av_frame_get_buffer(frame, 0);
        
        uint16_t *samples;

        ret = av_frame_make_writable(frame);
        frame->data[0] = (uint8_t *) audio.data();

        ret = avcodec_send_frame(codec_context_, frame);

        EncodedInfo info;
        
        while (ret >= 0)
        {
            ret = avcodec_receive_packet(codec_context_, pkt);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
                break;

            encoded->AppendData(pkt->data, pkt->size);
            av_packet_unref(pkt);
        }
        
        info.encoded_timestamp = rtp_timestamp;
        info.payload_type = payload_type_;
        info.send_even_if_empty = true;
        info.speech = true;
        info.encoded_bytes = encoded->size();
        
        return info;
    }
}

