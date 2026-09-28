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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_DECODER_WRAPPER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_DECODER_WRAPPER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video/encoded_image.h"
#include "api/video_codecs/video_decoder.h"
#include "common_video/h264/h264_bitstream_parser.h"
#include "rtc_base/synchronization/mutex.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <optional>
#include <string>

namespace jni
{
	// Runs a Java VideoDecoder inside WebRTC.
	//
	// WebRTC calls the decoder from one thread at a time, while the Java
	// decoder may hand its frames over from any thread, through a callback
	// that reaches this wrapper only until the decoder is released.
	class VideoDecoderWrapper : public webrtc::VideoDecoder
	{
		public:
			VideoDecoderWrapper(JNIEnv * env, jobject decoder);
			~VideoDecoderWrapper() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

			// Takes a frame from the Java decoder. Called through the Java
			// callback, which holds its lock meanwhile.
			void OnDecodedFrame(JNIEnv * env, jobject frame, std::optional<int32_t> decodeTimeMs,
				std::optional<uint8_t> qp);

		private:
			struct FrameExtraInfo
			{
				// Identifies the frame.
				int64_t timestampNs;
				uint32_t rtpTimestamp;
				int64_t ntpTimeMs;
				std::optional<uint8_t> qp;
			};

			bool ConfigureInternal(JNIEnv * env);
			int32_t ReleaseInternal(JNIEnv * env);
			int32_t HandleReturnCode(JNIEnv * env, int32_t status, const char * method);
			void InvalidateCallback(JNIEnv * env);

			std::optional<uint8_t> ParseQp(const webrtc::EncodedImage & image);

		private:
			class JavaVideoDecoderClass : public JavaClass
			{
				public:
					explicit JavaVideoDecoderClass(JNIEnv * env);

					jmethodID initDecode;
					jmethodID release;
					jmethodID decode;
					jmethodID getImplementationName;
					jmethodID isHardwareDecoder;

					jclass settingsClass;
					jmethodID settingsCtor;

					jclass callbackClass;
					jmethodID callbackCtor;
					jmethodID callbackInvalidate;
			};

		private:
			const JavaGlobalRef<jobject> decoder;
			const std::shared_ptr<JavaVideoDecoderClass> javaClass;

			std::string implementationName;
			bool hardwareAccelerated;

			// The callback the Java decoder was initialized with.
			std::unique_ptr<JavaGlobalRef<jobject>> javaCallback;

			std::atomic<webrtc::DecodedImageCallback *> callback;

			// Written on the decoder thread and read on the Java decoder's.
			webrtc::Mutex frameExtraInfosLock;
			std::deque<FrameExtraInfo> frameExtraInfos;

			Settings settings;
			bool initialized;

			// Parsing stops while the decoder provides the QP itself.
			bool qpParsingEnabled;
			webrtc::H264BitstreamParser h264BitstreamParser;
	};
}

#endif
