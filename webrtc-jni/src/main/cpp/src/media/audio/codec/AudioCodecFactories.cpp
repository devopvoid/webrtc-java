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

#include "media/audio/codec/AudioCodecFactories.h"
#include "media/audio/codec/FilteredAudioDecoderFactory.h"
#include "media/audio/codec/FilteredAudioEncoderFactory.h"
#include "Exception.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/make_ref_counted.h"

#include <string>
#include <vector>

namespace jni
{
	namespace
	{
		// Reads the canonical codec names a Java built-in factory holds. The
		// built-in factories are the only subclasses Java allows, and both
		// keep the names in the same field.
		std::vector<std::string> GetCodecNames(JNIEnv * env, const JavaRef<jobject> & factory)
		{
			std::vector<std::string> names;

			if (factory.get() == nullptr) {
				return names;
			}

			JavaLocalRef<jclass> cls(env, env->GetObjectClass(factory.get()));
			jfieldID field = env->GetFieldID(cls.get(), "codecNames", "[Ljava/lang/String;");

			if (field == nullptr) {
				env->ExceptionClear();
				throw Exception("The audio codec factory is not a built-in factory");
			}

			JavaLocalRef<jobjectArray> array(env, static_cast<jobjectArray>(env->GetObjectField(factory.get(), field)));
			jsize count = (array.get() != nullptr) ? env->GetArrayLength(array.get()) : 0;

			for (jsize i = 0; i < count; i++) {
				JavaLocalRef<jstring> name(env, static_cast<jstring>(env->GetObjectArrayElement(array.get(), i)));

				names.push_back(JavaString::toNative(env, name));
			}

			return names;
		}
	}

	webrtc::scoped_refptr<webrtc::AudioEncoderFactory> CreateAudioEncoderFactory(JNIEnv * env,
		const JavaRef<jobject> & factory)
	{
		auto builtin = webrtc::CreateBuiltinAudioEncoderFactory();
		auto names = GetCodecNames(env, factory);

		if (names.empty()) {
			return builtin;
		}

		return webrtc::make_ref_counted<FilteredAudioEncoderFactory>(builtin, std::move(names));
	}

	webrtc::scoped_refptr<webrtc::AudioDecoderFactory> CreateAudioDecoderFactory(JNIEnv * env,
		const JavaRef<jobject> & factory)
	{
		auto builtin = webrtc::CreateBuiltinAudioDecoderFactory();
		auto names = GetCodecNames(env, factory);

		if (names.empty()) {
			return builtin;
		}

		return webrtc::make_ref_counted<FilteredAudioDecoderFactory>(builtin, std::move(names));
	}
}
