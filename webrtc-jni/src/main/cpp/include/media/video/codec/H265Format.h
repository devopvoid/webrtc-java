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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_H265_FORMAT_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_H265_FORMAT_H_

#include "api/video_codecs/sdp_video_format.h"

namespace jni
{
	// The H.265 format the hardware codec factories offer: Main profile, main
	// tier, level 3.1, one RTP stream (tx-mode SRST). The same level as the
	// H.264 they offer; WebRTC negotiates the level down to what both ends
	// take.
	webrtc::SdpVideoFormat CreateH265MainFormat();
}

#endif
