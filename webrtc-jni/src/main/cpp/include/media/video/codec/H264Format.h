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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_H264_FORMAT_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_H264_FORMAT_H_

#include "api/video_codecs/sdp_video_format.h"

#include <vector>

namespace jni
{
	// The H.264 formats the hardware decoders offer: those WebRTC decodes,
	// without High 4:4:4 Predictive. GPU decoders take 4:2:0 only, so a 4:4:4
	// stream goes to the software decoder from its start, instead of failing
	// on the GPU and waiting for a key frame.
	std::vector<webrtc::SdpVideoFormat> CreateH264HardwareDecoderFormats();
}

#endif
