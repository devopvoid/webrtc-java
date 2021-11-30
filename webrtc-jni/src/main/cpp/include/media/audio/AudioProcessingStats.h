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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_PROCESSING_STATS_H_
#define JNI_WEBRTC_MEDIA_AUDIO_PROCESSING_STATS_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "modules/audio_processing/include/audio_processing.h"

#include <jni.h>

namespace jni
{
	namespace AudioProcessingStats
	{
		class JavaAudioProcessingStatsClass : public JavaClass
		{
			public:
				explicit JavaAudioProcessingStatsClass(JNIEnv * env);

				jclass cls;
				jfieldID voiceDetected;
				jfieldID echoReturnLoss;
				jfieldID echoReturnLossEnhancement;
				jfieldID divergentFilterFraction;
				jfieldID delayMs;
				jfieldID delayMedianMs;
				jfieldID delayStandardDeviationMs;
				jfieldID residualEchoLikelihood;
				jfieldID residualEchoLikelihoodRecentMax;
		};
	}
}

#endif