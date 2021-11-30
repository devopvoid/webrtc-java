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

#include "media/audio/AudioProcessingStats.h"

#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioProcessingStats
	{
		JavaAudioProcessingStatsClass::JavaAudioProcessingStatsClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingStats");

			voiceDetected = GetFieldID(env, cls, "voiceDetected", "Z");
			echoReturnLoss = GetFieldID(env, cls, "echoReturnLoss", "D");
			echoReturnLossEnhancement = GetFieldID(env, cls, "echoReturnLossEnhancement", "D");
			divergentFilterFraction = GetFieldID(env, cls, "divergentFilterFraction", "D");
			delayMs = GetFieldID(env, cls, "delayMs", "I");
			delayMedianMs = GetFieldID(env, cls, "delayMedianMs", "I");
			delayStandardDeviationMs = GetFieldID(env, cls, "delayStandardDeviationMs", "I");
			residualEchoLikelihood = GetFieldID(env, cls, "residualEchoLikelihood", "D");
			residualEchoLikelihoodRecentMax = GetFieldID(env, cls, "residualEchoLikelihoodRecentMax", "D");
		}
	}
}