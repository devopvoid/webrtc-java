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
				jfieldID pipeline;
				jfieldID echoCanceller;
				jfieldID gainController;
				jfieldID gainControllerDigital;
				jfieldID highPassFilter;
				jfieldID noiseSuppression;
				jfieldID captureLevelAdjustment;
		};

		webrtc::AudioProcessing::Config toNative(JNIEnv * env, const JavaRef<jobject> & javaType);
		webrtc::AudioProcessing::Config::Pipeline toPipeline(JNIEnv* env, const JavaLocalRef<jobject> & javaType);
		webrtc::AudioProcessing::Config::GainController1 toGainController1(JNIEnv * env, const JavaLocalRef<jobject> & javaType);
		webrtc::AudioProcessing::Config::GainController2 toGainController2(JNIEnv * env, const JavaLocalRef<jobject> & javaType);


		class JavaPipelineClass : public JavaClass
		{
			public:
				explicit JavaPipelineClass(JNIEnv * env);

				jclass cls;
				jfieldID maximumInternalProcessingRate;
				jfieldID multiChannelRender;
				jfieldID multiChannelCapture;
				jfieldID captureDownmixMethod;
		};

		class JavaEchoCancellerClass : public JavaClass
		{
			public:
				explicit JavaEchoCancellerClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID enforceHighPassFiltering;
		};

		class JavaGainControllerDigitalClass : public JavaClass
		{
			public:
				explicit JavaGainControllerDigitalClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID inputVolumeController;
				jfieldID fixedDigital;
				jfieldID adaptiveDigital;
		};

		class JavaInputVolumeControllerClass : public JavaClass
		{
			public:
				explicit JavaInputVolumeControllerClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
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
				jfieldID headroomDb;
				jfieldID maxGainDb;
				jfieldID initialGainDb;
				jfieldID maxGainChangeDbPerSecond;
				jfieldID maxOutputNoiseLevelDbfs;
		};

		class JavaGainControllerClass : public JavaClass
		{
			public:
				explicit JavaGainControllerClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID targetLevelDbfs;
				jfieldID compressionGainDb;
				jfieldID enableLimiter;
				jfieldID mode;
				jfieldID analogGainController;
		};

		class JavaAgc1AnalogGainControllerClass : public JavaClass
		{
			public:
				explicit JavaAgc1AnalogGainControllerClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID startupMinVolume;
				jfieldID clippedLevelMin;
				jfieldID enableDigitalAdaptive;
				jfieldID clippedLevelStep;
				jfieldID clippedRatioThreshold;
				jfieldID clippedWaitFrames;
				jfieldID clippingPredictor;
		};

		class JavaAgc1ClippingPredictorClass : public JavaClass
		{
			public:
				explicit JavaAgc1ClippingPredictorClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID mode;
				jfieldID windowLength;
				jfieldID referenceWindowLength;
				jfieldID referenceWindowDelay;
				jfieldID clippingThreshold;
				jfieldID crestFactorMargin;
				jfieldID usePredictedStep;
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

		class JavaCaptureLevelAdjustmentClass : public JavaClass
		{
			public:
				explicit JavaCaptureLevelAdjustmentClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID preGainFactor;
				jfieldID postGainFactor;
				jfieldID analogMicGainEmulation;
		};

		class JavaAnalogMicGainEmulationClass : public JavaClass
		{
			public:
				explicit JavaAnalogMicGainEmulationClass(JNIEnv * env);

				jclass cls;
				jfieldID enabled;
				jfieldID initialLevel;
		};
	}
}

#endif