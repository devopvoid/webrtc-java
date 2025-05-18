#ifndef JNI_WEBRTC_API_AUDIO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_API_AUDIO_ENCODER_FACTORY_H_

#include <api/scoped_refptr.h>

#include "api/audio_codecs/audio_encoder_factory.h"
#include <libavutil/opt.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/common.h>
#include <libavutil/imgutils.h>
#include <libavutil/mathematics.h>
#include <libavutil/samplefmt.h>

namespace webrtc
{
    rtc::scoped_refptr<AudioEncoderFactory> CreateFfmpegAudioEncoderFactory();
}

#endif