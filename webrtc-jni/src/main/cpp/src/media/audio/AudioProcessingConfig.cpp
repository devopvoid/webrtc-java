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
#include "JavaEnums.h"
#include "JavaObject.h"
#include "JavaRef.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioProcessingConfig
	{
		webrtc::AudioProcessing::Config toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaAudioProcessingConfigClass>(env);
			const auto javaEchoCancellerClass = JavaClasses::get<JavaEchoCancellerClass>(env);
			const auto javaHighPassFilterClass = JavaClasses::get<JavaHighPassFilterClass>(env);
			const auto javaNoiseSuppressionClass = JavaClasses::get<JavaNoiseSuppressionClass>(env);
			
			JavaObject obj(env, javaType);
			JavaObject echoCanceller(env, obj.getObject(javaClass->echoCanceller));
			JavaObject highPassFilter(env, obj.getObject(javaClass->highPassFilter));
			JavaObject noiseSuppression(env, obj.getObject(javaClass->noiseSuppression));

			webrtc::AudioProcessing::Config config;
			
			config.echo_canceller.enabled = echoCanceller.getBoolean(javaEchoCancellerClass->enabled);
			config.echo_canceller.enforce_high_pass_filtering = echoCanceller.getBoolean(javaEchoCancellerClass->enforceHighPassFiltering);
			config.gain_controller2 = toGainController2(env, obj.getObject(javaClass->gainControl));
			config.high_pass_filter.enabled = highPassFilter.getBoolean(javaHighPassFilterClass->enabled);
			config.noise_suppression.enabled = noiseSuppression.getBoolean(javaNoiseSuppressionClass->enabled);

			JavaLocalRef<jobject> nsLevel = noiseSuppression.getObject(javaNoiseSuppressionClass->level);

			if (nsLevel.get()) {
				config.noise_suppression.level = jni::JavaEnums::toNative<webrtc::AudioProcessing::Config::NoiseSuppression::Level>(env, nsLevel);
			}
			
			return config;
		}

		webrtc::AudioProcessing::Config::GainController2 toGainController2(JNIEnv * env, const JavaLocalRef<jobject> & javaType)
		{
			const auto javaGainControlClass = JavaClasses::get<JavaGainControlClass>(env);
			const auto javaGainControlFixedDigitalClass = JavaClasses::get<JavaGainControlFixedDigitalClass>(env);
			const auto javaGainControlAdaptiveDigitalClass = JavaClasses::get<JavaGainControlAdaptiveDigitalClass>(env);

			JavaObject gainControl(env, javaType);
			JavaObject gainControlFixedDigital(env, gainControl.getObject(javaGainControlClass->fixedDigital));
			JavaObject gainControlAdaptiveDigital(env, gainControl.getObject(javaGainControlClass->adaptiveDigital));

			webrtc::AudioProcessing::Config::GainController2 gainController;

			gainController.enabled = gainControl.getBoolean(javaGainControlClass->enabled);
			gainController.fixed_digital.gain_db = gainControlFixedDigital.getFloat(javaGainControlFixedDigitalClass->gainDb);
			gainController.adaptive_digital.enabled = gainControlAdaptiveDigital.getBoolean(javaGainControlAdaptiveDigitalClass->enabled);
			gainController.adaptive_digital.headroom_db = gainControlAdaptiveDigital.getFloat(javaGainControlAdaptiveDigitalClass->headroomDb);
			gainController.adaptive_digital.initial_gain_db = gainControlAdaptiveDigital.getFloat(javaGainControlAdaptiveDigitalClass->initialGainDb);
			gainController.adaptive_digital.max_gain_db = gainControlAdaptiveDigital.getFloat(javaGainControlAdaptiveDigitalClass->maxGainDb);
			gainController.adaptive_digital.max_gain_change_db_per_second = gainControlAdaptiveDigital.getFloat(javaGainControlAdaptiveDigitalClass->maxGainChangeDbPerSecond);
			gainController.adaptive_digital.max_output_noise_level_dbfs = gainControlAdaptiveDigital.getFloat(javaGainControlAdaptiveDigitalClass->maxOutputNoiseLevelDbfs);

			return gainController;
		}

		JavaAudioProcessingConfigClass::JavaAudioProcessingConfigClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig");

			echoCanceller = GetFieldID(env, cls, "echoCanceller", "L" PKG_AUDIO "AudioProcessingConfig$EchoCanceller;");
			gainControl = GetFieldID(env, cls, "gainControl", "L" PKG_AUDIO "AudioProcessingConfig$GainControl;");
			highPassFilter = GetFieldID(env, cls, "highPassFilter", "L" PKG_AUDIO "AudioProcessingConfig$HighPassFilter;");
			noiseSuppression = GetFieldID(env, cls, "noiseSuppression", "L" PKG_AUDIO "AudioProcessingConfig$NoiseSuppression;");
			transientSuppression = GetFieldID(env, cls, "transientSuppression", "L" PKG_AUDIO "AudioProcessingConfig$TransientSuppression;");
		}

		JavaEchoCancellerClass::JavaEchoCancellerClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$EchoCanceller");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			enforceHighPassFiltering = GetFieldID(env, cls, "enforceHighPassFiltering", "Z");
		}

		JavaGainControlClass::JavaGainControlClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainControl");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			fixedDigital = GetFieldID(env, cls, "fixedDigital", "L" PKG_AUDIO "AudioProcessingConfig$GainControl$FixedDigital;");
			adaptiveDigital = GetFieldID(env, cls, "adaptiveDigital", "L" PKG_AUDIO "AudioProcessingConfig$GainControl$AdaptiveDigital;");
		}

		JavaGainControlFixedDigitalClass::JavaGainControlFixedDigitalClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainControl$FixedDigital");

			gainDb = GetFieldID(env, cls, "gainDb", "F");
		}

		JavaGainControlAdaptiveDigitalClass::JavaGainControlAdaptiveDigitalClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainControl$AdaptiveDigital");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			headroomDb = GetFieldID(env, cls, "headroomDb", "F");
			maxGainDb = GetFieldID(env, cls, "maxGainDb", "F");
			initialGainDb = GetFieldID(env, cls, "initialGainDb", "F");
			maxGainChangeDbPerSecond = GetFieldID(env, cls, "maxGainChangeDbPerSecond", "F");
			maxOutputNoiseLevelDbfs = GetFieldID(env, cls, "maxOutputNoiseLevelDbfs", "F");
		}

		JavaHighPassFilterClass::JavaHighPassFilterClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$HighPassFilter");

			enabled = GetFieldID(env, cls, "enabled", "Z");
		}

		JavaNoiseSuppressionClass::JavaNoiseSuppressionClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$NoiseSuppression");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			level = GetFieldID(env, cls, "level", "L" PKG_AUDIO "AudioProcessingConfig$NoiseSuppression$Level;");
		}
	}
}