#ifndef JNI_WEBRTC_API_AUDIO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_API_AUDIO_ENCODER_FACTORY_H_

#include <api/scoped_refptr.h>
#include <vector>
#include <string>

#include "api/audio_codecs/audio_encoder_factory.h"

extern "C"
{
#include <libavutil/opt.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/common.h>
#include <libavutil/imgutils.h>
#include <libavutil/mathematics.h>
#include <libavutil/samplefmt.h>
}

namespace webrtc
{

    // FFmpeg 오디오 인코더 팩토리 생성
    rtc::scoped_refptr<AudioEncoderFactory> CreateFfmpegAudioEncoderFactory();

    // 특정 코덱만 지원하는 FFmpeg 오디오 인코더 팩토리 생성
    rtc::scoped_refptr<AudioEncoderFactory> CreateFfmpegAudioEncoderFactory(
        const std::vector<std::string> &codec_names);

    // 사용 가능한 FFmpeg 오디오 인코더 목록 가져오기
    std::vector<std::string> GetSupportedFfmpegAudioEncoders();

} // namespace webrtc

#endif // JNI_WEBRTC_API_AUDIO_ENCODER_FACTORY_H_