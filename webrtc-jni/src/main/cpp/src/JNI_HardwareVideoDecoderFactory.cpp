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

#include "JNI_HardwareVideoDecoderFactory.h"
#include "media/video/codec/DefaultVideoCodecFactories.h"
#include "media/video/codec/VideoCodecInfo.h"
#include "JavaUtils.h"

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_media_video_codec_HardwareVideoDecoderFactory_getSupportedCodecsInternal
(JNIEnv * env, jclass caller)
{
	try {
		auto factory = jni::CreateHardwareVideoDecoderFactory();

		return jni::VideoCodecInfo::toJavaArray(env, factory->GetSupportedFormats()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return nullptr;
}
