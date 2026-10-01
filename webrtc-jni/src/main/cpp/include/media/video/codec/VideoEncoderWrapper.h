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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_ENCODER_WRAPPER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_ENCODER_WRAPPER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video/encoded_image.h"
#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"
#include "common_video/h264/h264_bitstream_parser.h"
#include "modules/video_coding/codecs/vp9/include/vp9_globals.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/svc/scalable_video_controller_no_layering.h"
#include "rtc_base/synchronization/mutex.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <optional>
#include <vector>

namespace jni
{
	// Runs a Java VideoEncoder inside WebRTC.
	//
	// WebRTC calls the encoder from one thread at a time, while the Java
	// encoder may hand its frames over from any thread, through a callback
	// that reaches this wrapper only until the encoder is released.
	class VideoEncoderWrapper : public webrtc::VideoEncoder
	{
		public:
			VideoEncoderWrapper(JNIEnv * env, jobject encoder, const webrtc::SdpVideoFormat & format);
			~VideoEncoderWrapper() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

			// Takes a frame from the Java encoder. Called through the Java
			// callback, which holds its lock meanwhile.
			void OnEncodedFrame(JNIEnv * env, jobject image);

		private:
			struct FrameExtraInfo
			{
				// Identifies the frame.
				int64_t captureTimeNs;
				uint32_t rtpTimestamp;
			};

			int32_t InitEncodeInternal(JNIEnv * env);
			int32_t ReleaseInternal(JNIEnv * env);
			// Handles a status the Java encoder returned: tries to reset the
			// encoder on errors WebRTC does not give up on.
			int32_t HandleReturnCode(JNIEnv * env, int32_t status, const char * method);
			void InvalidateCallback(JNIEnv * env);
			void UpdateEncoderInfo(JNIEnv * env);

			ScalingSettings GetScalingSettings(JNIEnv * env) const;
			std::vector<ResolutionBitrateLimits> GetResolutionBitrateLimits(JNIEnv * env) const;

			int ParseQp(const webrtc::EncodedImage & image);
			webrtc::CodecSpecificInfo ParseCodecSpecificInfo(const webrtc::EncodedImage & image);

		private:
			class JavaVideoEncoderClass : public JavaClass
			{
				public:
					explicit JavaVideoEncoderClass(JNIEnv * env);

					jmethodID initEncode;
					jmethodID release;
					jmethodID encode;
					jmethodID setRates;
					jmethodID getScalingSettings;
					jmethodID getResolutionBitrateLimits;
					jmethodID getEncoderInfo;
					jmethodID getImplementationName;
					jmethodID isHardwareEncoder;

					jclass settingsClass;
					jmethodID settingsCtor;

					jclass encodeInfoClass;
					jmethodID encodeInfoFromNative;

					jclass bitrateAllocationClass;
					jmethodID bitrateAllocationCtor;

					jclass rateControlClass;
					jmethodID rateControlCtor;

					jfieldID scalingOn;
					jfieldID scalingLow;
					jfieldID scalingHigh;

					jfieldID limitsFrameSizePixels;
					jfieldID limitsMinStartBitrateBps;
					jfieldID limitsMinBitrateBps;
					jfieldID limitsMaxBitrateBps;

					jfieldID infoRequestedResolutionAlignment;
					jfieldID infoApplyAlignmentToAllSimulcastLayers;

					jclass callbackClass;
					jmethodID callbackCtor;
					jmethodID callbackInvalidate;

					jclass intArrayClass;
			};

		private:
			const JavaGlobalRef<jobject> encoder;
			const std::shared_ptr<JavaVideoEncoderClass> javaClass;
			const webrtc::SdpVideoFormat format;

			// The callback the Java encoder was initialized with.
			std::unique_ptr<JavaGlobalRef<jobject>> javaCallback;

			std::atomic<webrtc::EncodedImageCallback *> callback;

			// Written on the encoder thread and read on the Java encoder's.
			webrtc::Mutex frameExtraInfosLock;
			std::deque<FrameExtraInfo> frameExtraInfos;

			bool initialized;
			std::optional<webrtc::VideoEncoder::Capabilities> capabilities;
			int numberOfCores;
			webrtc::VideoCodec codecSettings;
			EncoderInfo encoderInfo;

			// Used on the thread the Java encoder hands its frames over on.
			webrtc::H264BitstreamParser h264BitstreamParser;
			webrtc::ScalableVideoControllerNoLayering svcController;
			webrtc::GofInfoVP9 gof;
			size_t gofIndex;
	};
}

#endif
