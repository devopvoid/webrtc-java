/*
 *  Copyright (c) 2019 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#ifndef JNI_WEBRTC_API_AUDIO_DECODER_FACTORY_H_
#define JNI_WEBRTC_API_AUDIO_DECODER_FACTORY_H_

#include <api/scoped_refptr.h>
#include <vector>
#include <string>

#include "api/audio_codecs/audio_decoder_factory.h"

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

    // FFmpeg 오디오 디코더 팩토리 생성
    rtc::scoped_refptr<AudioDecoderFactory> CreateFfmpegAudioDecoderFactory();

    // 특정 코덱만 지원하는 FFmpeg 오디오 디코더 팩토리 생성
    rtc::scoped_refptr<AudioDecoderFactory> CreateFfmpegAudioDecoderFactory(
        const std::vector<std::string> &codec_names);

    // 사용 가능한 FFmpeg 오디오 디코더 목록 가져오기
    std::vector<std::string> GetSupportedFfmpegAudioDecoders();

    // 특정 오디오 코덱이 지원되는지 확인
    bool IsSupportedAudioDecoder(const std::string& codec_name);

} // namespace webrtc

#endif // JNI_WEBRTC_API_AUDIO_DECODER_FACTORY_H_
