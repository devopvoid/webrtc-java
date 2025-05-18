#include "media/audio/FfmpegAudioEncoder.h"

namespace webrtc {
    
    absl::optional<AVCodec> AudioEncoderFfmpeg::SdpToConfig(
        const SdpAudioFormat& format) {
        return AudioEncoderFfmpegImpl::SdpToConfig(format);
    }

    void AudioEncoderFfmpeg::AppendSupportedEncoders(
        std::vector<AudioCodecSpec>* specs) {
        AudioEncoderFfmpegImpl::AppendSupportedEncoders(specs);
    }

    AudioCodecInfo AudioEncoderFfmpeg::QueryAudioEncoder(
        const AudioEncoderOpusConfig& config) {
        return AudioEncoderOpusImpl::QueryAudioEncoder(config);
    }

    std::unique_ptr<AudioEncoder> AudioEncoderOpus::MakeAudioEncoder(
        const AudioEncoderOpusConfig& config,
        int payload_type,
        absl::optional<AudioCodecPairId> /*codec_pair_id*/) {
        if (!config.IsOk()) {
            RTC_DCHECK_NOTREACHED();
            return nullptr;
        }
        return AudioEncoderOpusImpl::MakeAudioEncoder(config, payload_type);
    }
    
}  // namespace webrtc
