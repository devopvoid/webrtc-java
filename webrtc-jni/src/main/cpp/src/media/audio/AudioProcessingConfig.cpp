/*
 * Copyright 2021 Alex Andres
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

#include "media/audio/AudioProcessingConfig.h"

#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioProcessingConfig
	{
		webrtc::AudioProcessing::Config toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaAudioProcessingConfigClass>(env);
			
			JavaObject obj(env, javaType);

			webrtc::AudioProcessing::Config config;
			config.echo_canceller.enabled = obj.getBoolean(javaClass->echoCancellerEnabled);
			config.residual_echo_detector.enabled = obj.getBoolean(javaClass->residualEchoDetectorEnabled);
			config.noise_suppression.enabled = obj.getBoolean(javaClass->noiseSuppressionEnabled);
			config.high_pass_filter.enabled = obj.getBoolean(javaClass->highPassFilterEnabled);
			config.level_estimation.enabled = obj.getBoolean(javaClass->levelEstimationEnabled);
			config.voice_detection.enabled = obj.getBoolean(javaClass->voiceDetectionEnabled);
			config.transient_suppression.enabled = obj.getBoolean(javaClass->transientSuppressionEnabled);

			return config;
		}

		JavaAudioProcessingConfigClass::JavaAudioProcessingConfigClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig");

			echoCancellerEnabled = GetFieldID(env, cls, "echoCancellerEnabled", "Z");
			residualEchoDetectorEnabled = GetFieldID(env, cls, "residualEchoDetectorEnabled", "Z");
			gainControlEnabled = GetFieldID(env, cls, "gainControlEnabled", "Z");
			highPassFilterEnabled = GetFieldID(env, cls, "highPassFilterEnabled", "Z");
			noiseSuppressionEnabled = GetFieldID(env, cls, "noiseSuppressionEnabled", "Z");
			transientSuppressionEnabled = GetFieldID(env, cls, "transientSuppressionEnabled", "Z");
			levelEstimationEnabled = GetFieldID(env, cls, "levelEstimationEnabled", "Z");
			voiceDetectionEnabled = GetFieldID(env, cls, "voiceDetectionEnabled", "Z");
		}
	}
}