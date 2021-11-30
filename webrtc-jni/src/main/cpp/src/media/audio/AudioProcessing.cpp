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

#include "media/audio/AudioProcessing.h"
#include "media/audio/AudioProcessingStats.h"

#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioProcessing
	{
		void updateStats(const webrtc::AudioProcessingStats & stats, JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaAudioProcessingClass>(env);
			const auto javaStatsClass = JavaClasses::get<AudioProcessingStats::JavaAudioProcessingStatsClass>(env);

			JavaObject obj(env, javaType);
			JavaObject statsObj(env, obj.getObject(javaClass->stats));

			statsObj.setBoolean(javaStatsClass->voiceDetected, stats.voice_detected.value_or(false));
			statsObj.setDouble(javaStatsClass->echoReturnLoss, stats.echo_return_loss.value_or(0));
			statsObj.setDouble(javaStatsClass->echoReturnLossEnhancement, stats.echo_return_loss_enhancement.value_or(0));
			statsObj.setDouble(javaStatsClass->divergentFilterFraction, stats.divergent_filter_fraction.value_or(0));
			statsObj.setInt(javaStatsClass->delayMs, stats.delay_ms.value_or(0));
			statsObj.setInt(javaStatsClass->delayMedianMs, stats.delay_median_ms.value_or(0));
			statsObj.setInt(javaStatsClass->delayStandardDeviationMs, stats.delay_standard_deviation_ms.value_or(0));
			statsObj.setDouble(javaStatsClass->residualEchoLikelihood, stats.residual_echo_likelihood.value_or(0));
			statsObj.setDouble(javaStatsClass->residualEchoLikelihoodRecentMax, stats.residual_echo_likelihood_recent_max.value_or(0));
		}

		JavaAudioProcessingClass::JavaAudioProcessingClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessing");

			stats = GetFieldID(env, cls, "stats", "L" PKG_AUDIO "AudioProcessingStats;");
		}
	}
}