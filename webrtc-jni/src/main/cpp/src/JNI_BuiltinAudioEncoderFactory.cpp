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

#include "JNI_BuiltinAudioEncoderFactory.h"
#include "media/audio/codec/AudioCodecInfo.h"
#include "JavaUtils.h"

#include "api/audio_codecs/builtin_audio_encoder_factory.h"

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_media_audio_codec_BuiltinAudioEncoderFactory_getBuiltinCodecs
(JNIEnv * env, jclass caller)
{
	try {
		auto factory = webrtc::CreateBuiltinAudioEncoderFactory();

		return jni::AudioCodecInfo::toJavaArray(env, factory->GetSupportedEncoders()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return nullptr;
}
