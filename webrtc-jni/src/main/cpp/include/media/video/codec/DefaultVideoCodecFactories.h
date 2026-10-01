/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_DEFAULT_VIDEO_CODEC_FACTORIES_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_DEFAULT_VIDEO_CODEC_FACTORIES_H_

#include "api/video_codecs/video_decoder_factory.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <memory>

namespace jni
{
	// The video codecs built into this library: VP8, VP9, AV1 and H.264 in
	// software, or on macOS those of WebRTC's default Objective-C factories,
	// which use VideoToolbox.
	std::unique_ptr<webrtc::VideoEncoderFactory> CreateDefaultVideoEncoderFactory();
	std::unique_ptr<webrtc::VideoDecoderFactory> CreateDefaultVideoDecoderFactory();
}

#endif
