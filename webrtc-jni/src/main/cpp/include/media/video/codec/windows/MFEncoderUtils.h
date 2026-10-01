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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_ENCODER_UTILS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_ENCODER_UTILS_H_

#include <mfapi.h>
#include <mfidl.h>
#include <wrl/client.h>

#include <string>
#include <vector>

namespace jni
{
	// Lists the hardware encoder transforms that take NV12 and produce the
	// given video format, such as MFVideoFormat_H264, best first. Media
	// Foundation has to be started.
	HRESULT EnumerateHardwareEncoders(const GUID & format, std::vector<Microsoft::WRL::ComPtr<IMFActivate>> & encoders);

	// Returns the name the driver gives a transform, e.g. "AMDh264Encoder".
	std::string GetTransformName(IMFActivate * activate);
}

#endif
