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
			const auto javaGainControllerClass = JavaClasses::get<JavaGainControllerClass>(env);
			
			JavaObject obj(env, javaType);
			JavaObject echoCanceller(env, obj.getObject(javaClass->echoCanceller));
			JavaObject highPassFilter(env, obj.getObject(javaClass->highPassFilter));
			JavaObject noiseSuppression(env, obj.getObject(javaClass->noiseSuppression));
			JavaObject gainController(env, obj.getObject(javaClass->gainController));

			webrtc::AudioProcessing::Config config;
			
			config.pipeline = toPipeline(env, obj.getObject(javaClass->pipeline));
			config.echo_canceller.enabled = echoCanceller.getBoolean(javaEchoCancellerClass->enabled);
			config.echo_canceller.enforce_high_pass_filtering = echoCanceller.getBoolean(javaEchoCancellerClass->enforceHighPassFiltering);
			config.gain_controller2 = toGainController2(env, obj.getObject(javaClass->gainControllerDigital));
			config.gain_controller1 = toGainController1(env, obj.getObject(javaClass->gainController));
			config.high_pass_filter.enabled = highPassFilter.getBoolean(javaHighPassFilterClass->enabled);
			config.noise_suppression.enabled = noiseSuppression.getBoolean(javaNoiseSuppressionClass->enabled);

			JavaLocalRef<jobject> nsLevel = noiseSuppression.getObject(javaNoiseSuppressionClass->level);

			if (nsLevel.get()) {
				config.noise_suppression.level = jni::JavaEnums::toNative<webrtc::AudioProcessing::Config::NoiseSuppression::Level>(env, nsLevel);
			}
			
			return config;
		}

		webrtc::AudioProcessing::Config::Pipeline toPipeline(JNIEnv * env, const JavaLocalRef<jobject> & javaType)
		{
			const auto javaPipelineClass = JavaClasses::get<JavaPipelineClass>(env);

			JavaObject jPipeline(env, javaType);

			JavaLocalRef<jobject> downmixMethod = jPipeline.getObject(javaPipelineClass->captureDownmixMethod);

			webrtc::AudioProcessing::Config::Pipeline pipeline;

			pipeline.maximum_internal_processing_rate = jPipeline.getInt(javaPipelineClass->maximumInternalProcessingRate);
			pipeline.multi_channel_render = jPipeline.getBoolean(javaPipelineClass->multiChannelRender);
			pipeline.multi_channel_capture = jPipeline.getBoolean(javaPipelineClass->multiChannelCapture);
			pipeline.capture_downmix_method = JavaEnums::toNative<webrtc::AudioProcessing::Config::Pipeline::DownmixMethod>(env, downmixMethod);

			return pipeline;
		}

		webrtc::AudioProcessing::Config::GainController2 toGainController2(JNIEnv * env, const JavaLocalRef<jobject> & javaType)
		{
			const auto javaGainControlClass = JavaClasses::get<JavaGainControllerDigitalClass>(env);
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

		webrtc::AudioProcessing::Config::GainController1 toGainController1(JNIEnv * env, const JavaLocalRef<jobject> & javaType)
		{
			const auto javaGainControllerClass = JavaClasses::get<JavaGainControllerClass>(env);
			const auto javaAgc1AnalogClass = JavaClasses::get<JavaAgc1AnalogGainControllerClass>(env);
			const auto javaAgc1ClipPredClass = JavaClasses::get<JavaAgc1ClippingPredictorClass>(env);

			JavaObject jGc1(env, javaType);
			webrtc::AudioProcessing::Config::GainController1 gc1;

			if (javaType.get()) {
				gc1.enabled = jGc1.getBoolean(javaGainControllerClass->enabled);
				gc1.target_level_dbfs = jGc1.getInt(javaGainControllerClass->targetLevelDbfs);
				gc1.compression_gain_db = jGc1.getInt(javaGainControllerClass->compressionGainDb);
				gc1.enable_limiter = jGc1.getBoolean(javaGainControllerClass->enableLimiter);

				JavaLocalRef<jobject> mode = jGc1.getObject(javaGainControllerClass->mode);
				if (mode.get()) {
					gc1.mode = JavaEnums::toNative<webrtc::AudioProcessing::Config::GainController1::Mode>(env, mode);
				}

				JavaObject jAgc(env, jGc1.getObject(javaGainControllerClass->analogGainController));

					gc1.analog_gain_controller.enabled = jAgc.getBoolean(javaAgc1AnalogClass->enabled);
					gc1.analog_gain_controller.startup_min_volume = jAgc.getInt(javaAgc1AnalogClass->startupMinVolume);
					gc1.analog_gain_controller.clipped_level_min = jAgc.getInt(javaAgc1AnalogClass->clippedLevelMin);
					gc1.analog_gain_controller.enable_digital_adaptive = jAgc.getBoolean(javaAgc1AnalogClass->enableDigitalAdaptive);
					gc1.analog_gain_controller.clipped_level_step = jAgc.getInt(javaAgc1AnalogClass->clippedLevelStep);
					gc1.analog_gain_controller.clipped_ratio_threshold = jAgc.getFloat(javaAgc1AnalogClass->clippedRatioThreshold);
					gc1.analog_gain_controller.clipped_wait_frames = jAgc.getInt(javaAgc1AnalogClass->clippedWaitFrames);

					JavaObject jPred(env, jAgc.getObject(javaAgc1AnalogClass->clippingPredictor));

						gc1.analog_gain_controller.clipping_predictor.enabled = jPred.getBoolean(javaAgc1ClipPredClass->enabled);
						JavaLocalRef<jobject> predMode = jPred.getObject(javaAgc1ClipPredClass->mode);
						if (predMode.get()) {
							gc1.analog_gain_controller.clipping_predictor.mode = JavaEnums::toNative<webrtc::AudioProcessing::Config::GainController1::AnalogGainController::ClippingPredictor::Mode>(env, predMode);
						}
						gc1.analog_gain_controller.clipping_predictor.window_length = jPred.getInt(javaAgc1ClipPredClass->windowLength);
						gc1.analog_gain_controller.clipping_predictor.reference_window_length = jPred.getInt(javaAgc1ClipPredClass->referenceWindowLength);
						gc1.analog_gain_controller.clipping_predictor.reference_window_delay = jPred.getInt(javaAgc1ClipPredClass->referenceWindowDelay);
						gc1.analog_gain_controller.clipping_predictor.clipping_threshold = jPred.getFloat(javaAgc1ClipPredClass->clippingThreshold);
						gc1.analog_gain_controller.clipping_predictor.crest_factor_margin = jPred.getFloat(javaAgc1ClipPredClass->crestFactorMargin);
						gc1.analog_gain_controller.clipping_predictor.use_predicted_step = jPred.getBoolean(javaAgc1ClipPredClass->usePredictedStep);
			}

			return gc1;
		}

		JavaAudioProcessingConfigClass::JavaAudioProcessingConfigClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig");

			pipeline = GetFieldID(env, cls, "pipeline", "L" PKG_AUDIO "AudioProcessingConfig$Pipeline;");
			echoCanceller = GetFieldID(env, cls, "echoCanceller", "L" PKG_AUDIO "AudioProcessingConfig$EchoCanceller;");
			gainControllerDigital = GetFieldID(env, cls, "gainControllerDigital", "L" PKG_AUDIO "AudioProcessingConfig$GainControllerDigital;");
			gainController = GetFieldID(env, cls, "gainController", "L" PKG_AUDIO "AudioProcessingConfig$GainController;");
			highPassFilter = GetFieldID(env, cls, "highPassFilter", "L" PKG_AUDIO "AudioProcessingConfig$HighPassFilter;");
			noiseSuppression = GetFieldID(env, cls, "noiseSuppression", "L" PKG_AUDIO "AudioProcessingConfig$NoiseSuppression;");
		}

		JavaPipelineClass::JavaPipelineClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$Pipeline");

			maximumInternalProcessingRate = GetFieldID(env, cls, "maximumInternalProcessingRate", "I");
			multiChannelRender = GetFieldID(env, cls, "multiChannelRender", "Z");
			multiChannelCapture = GetFieldID(env, cls, "multiChannelCapture", "Z");
			captureDownmixMethod = GetFieldID(env, cls, "captureDownmixMethod", "L" PKG_AUDIO "AudioProcessingConfig$Pipeline$DownmixMethod;");
		}

		JavaEchoCancellerClass::JavaEchoCancellerClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$EchoCanceller");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			enforceHighPassFiltering = GetFieldID(env, cls, "enforceHighPassFiltering", "Z");
		}

		JavaGainControllerDigitalClass::JavaGainControllerDigitalClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainControllerDigital");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			fixedDigital = GetFieldID(env, cls, "fixedDigital", "L" PKG_AUDIO "AudioProcessingConfig$GainControllerDigital$FixedDigital;");
			adaptiveDigital = GetFieldID(env, cls, "adaptiveDigital", "L" PKG_AUDIO "AudioProcessingConfig$GainControllerDigital$AdaptiveDigital;");
		}

		JavaGainControlFixedDigitalClass::JavaGainControlFixedDigitalClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainControllerDigital$FixedDigital");

			gainDb = GetFieldID(env, cls, "gainDb", "F");
		}

		JavaGainControlAdaptiveDigitalClass::JavaGainControlAdaptiveDigitalClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainControllerDigital$AdaptiveDigital");

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

		JavaGainControllerClass::JavaGainControllerClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainController");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			targetLevelDbfs = GetFieldID(env, cls, "targetLevelDbfs", "I");
			compressionGainDb = GetFieldID(env, cls, "compressionGainDb", "I");
			enableLimiter = GetFieldID(env, cls, "enableLimiter", "Z");
			mode = GetFieldID(env, cls, "mode", "L" PKG_AUDIO "AudioProcessingConfig$GainController$Mode;");
			analogGainController = GetFieldID(env, cls, "analogGainController", "L" PKG_AUDIO "AudioProcessingConfig$GainController$AnalogGainController;");
		}

		JavaAgc1AnalogGainControllerClass::JavaAgc1AnalogGainControllerClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainController$AnalogGainController");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			startupMinVolume = GetFieldID(env, cls, "startupMinVolume", "I");
			clippedLevelMin = GetFieldID(env, cls, "clippedLevelMin", "I");
			enableDigitalAdaptive = GetFieldID(env, cls, "enableDigitalAdaptive", "Z");
			clippedLevelStep = GetFieldID(env, cls, "clippedLevelStep", "I");
			clippedRatioThreshold = GetFieldID(env, cls, "clippedRatioThreshold", "F");
			clippedWaitFrames = GetFieldID(env, cls, "clippedWaitFrames", "I");
			clippingPredictor = GetFieldID(env, cls, "clippingPredictor", "L" PKG_AUDIO "AudioProcessingConfig$GainController$AnalogGainController$ClippingPredictor;");
		}

		JavaAgc1ClippingPredictorClass::JavaAgc1ClippingPredictorClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$GainController$AnalogGainController$ClippingPredictor");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			mode = GetFieldID(env, cls, "mode", "L" PKG_AUDIO "AudioProcessingConfig$GainController$AnalogGainController$ClippingPredictor$Mode;");
			windowLength = GetFieldID(env, cls, "windowLength", "I");
			referenceWindowLength = GetFieldID(env, cls, "referenceWindowLength", "I");
			referenceWindowDelay = GetFieldID(env, cls, "referenceWindowDelay", "I");
			clippingThreshold = GetFieldID(env, cls, "clippingThreshold", "F");
			crestFactorMargin = GetFieldID(env, cls, "crestFactorMargin", "F");
			usePredictedStep = GetFieldID(env, cls, "usePredictedStep", "Z");
		}

		JavaNoiseSuppressionClass::JavaNoiseSuppressionClass(JNIEnv* env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingConfig$NoiseSuppression");

			enabled = GetFieldID(env, cls, "enabled", "Z");
			level = GetFieldID(env, cls, "level", "L" PKG_AUDIO "AudioProcessingConfig$NoiseSuppression$Level;");
		}
	}
}