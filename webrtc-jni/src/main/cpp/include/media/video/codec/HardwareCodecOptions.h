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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_HARDWARE_CODEC_OPTIONS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_HARDWARE_CODEC_OPTIONS_H_

namespace jni
{
	// The Java system property that turns on hardware VP9 encoding on
	// Windows, which is off by default: only Intel GPUs encode VP9, and the
	// Media Foundation encoder has not been tested on one. VA-API encodes VP9
	// on Linux regardless.
	constexpr const char * kHardwareVp9EncoderProperty = "webrtc.hardware.vp9Encoder";

	// Whether hardware VP9 encoding is turned on: the Java system property
	// above is "true", as Boolean.getBoolean has it. Callable from any thread;
	// false where Java cannot be asked, for one with an exception pending.
	bool IsHardwareVp9EncoderEnabled();
}

#endif
