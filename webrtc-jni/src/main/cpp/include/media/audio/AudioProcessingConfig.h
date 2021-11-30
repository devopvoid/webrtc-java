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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_PROCESSING_CONFIG_H_
#define JNI_WEBRTC_MEDIA_AUDIO_PROCESSING_CONFIG_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "modules/audio_processing/include/audio_processing.h"

#include <jni.h>

namespace jni
{
	namespace AudioProcessingConfig
	{
		class JavaAudioProcessingConfigClass : public JavaClass
		{
			public:
				explicit JavaAudioProcessingConfigClass(JNIEnv * env);

				jclass cls;
				jfieldID echoCanceller;
				jfieldID gainControl;
				jfieldID highPassFilter;
				jfieldID noiseSuppression;
				jfieldID residualEchoDetector;
				jfieldID transientSuppression;
				jfieldID voiceDetection;
		};

		webrtc::AudioProcessing::Config toNative(JNIEnv * env, const JavaRef<jobject> & javaType);
		webrtc::AudioProcessing::Config::GainController2 toGainController2(JNIEnv * env, const JavaLocalRef<jobject> & javaType);


		class JavaEchoCancellerClass : public JavaClass
		{
			public:
				explicit JavaEchoCancellerClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID enforceHighPassFiltering;
		};

		class JavaGainControlClass : public JavaClass
		{
			public:
				explicit JavaGainControlClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID fixedDigital;
				jfieldID adaptiveDigital;
		};

		class JavaGainControlFixedDigitalClass : public JavaClass
		{
			public:
				explicit JavaGainControlFixedDigitalClass(JNIEnv * env);

				jclass cls;
				jfieldID gainDb;
		};

		class JavaGainControlAdaptiveDigitalClass : public JavaClass
		{
			public:
				explicit JavaGainControlAdaptiveDigitalClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID dryRun;
				jfieldID vadResetPeriodMs;
				jfieldID adjacentSpeechFramesThreshold;
				jfieldID maxGainChangeDbPerSecond;
				jfieldID maxOutputNoiseLevelDbfs;
		};

		class JavaHighPassFilterClass : public JavaClass
		{
			public:
				explicit JavaHighPassFilterClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
		};

		class JavaNoiseSuppressionClass : public JavaClass
		{
			public:
				explicit JavaNoiseSuppressionClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID level;
		};

		class JavaResidualEchoDetectorClass : public JavaClass
		{
			public:
				explicit JavaResidualEchoDetectorClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
		};

		class JavaTransientSuppressionClass : public JavaClass
		{
			public:
				explicit JavaTransientSuppressionClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
		};

		class JavaVoiceDetectionClass : public JavaClass
		{
			public:
				explicit JavaVoiceDetectionClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
		};
	}
}

#endif