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

#include "JNI_BuiltinAudioDecoderFactory.h"
#include "media/audio/codec/AudioCodecInfo.h"
#include "JavaUtils.h"

#include "api/audio_codecs/builtin_audio_decoder_factory.h"

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_media_audio_codec_BuiltinAudioDecoderFactory_getBuiltinCodecs
(JNIEnv * env, jclass caller)
{
	try {
		auto factory = webrtc::CreateBuiltinAudioDecoderFactory();

		return jni::AudioCodecInfo::toJavaArray(env, factory->GetSupportedDecoders()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return nullptr;
}
