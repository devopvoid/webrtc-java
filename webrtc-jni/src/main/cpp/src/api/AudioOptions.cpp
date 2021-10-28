/*
 * Copyright 2019 Alex Andres
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

#include "api/AudioOptions.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioOptions
	{
		cricket::AudioOptions toNative(JNIEnv * env, const JavaRef<jobject>& javaType)
		{
			const auto javaClass = JavaClasses::get<JavaAudioOptionsClass>(env);

			JavaObject obj(env, javaType);

			auto options = cricket::AudioOptions();
			options.echo_cancellation = obj.getBoolean(javaClass->echoCancellation);
			options.auto_gain_control = obj.getBoolean(javaClass->autoGainControl);
			options.noise_suppression = obj.getBoolean(javaClass->noiseSuppression);
			options.highpass_filter = obj.getBoolean(javaClass->highpassFilter);
			options.typing_detection = obj.getBoolean(javaClass->typingDetection);
			options.residual_echo_detector = obj.getBoolean(javaClass->residualEchoDetector);

			return options;
		}

		JavaAudioOptionsClass::JavaAudioOptionsClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioOptions");
	
			echoCancellation = GetFieldID(env, cls, "echoCancellation", "Z");
			autoGainControl = GetFieldID(env, cls, "autoGainControl", "Z");
			noiseSuppression = GetFieldID(env, cls, "noiseSuppression", "Z");
			highpassFilter = GetFieldID(env, cls, "highpassFilter", "Z");
			typingDetection = GetFieldID(env, cls, "typingDetection", "Z");
			residualEchoDetector = GetFieldID(env, cls, "residualEchoDetector", "Z");
		}
	}
}