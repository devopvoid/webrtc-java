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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_ENCODER_FACTORY_WRAPPER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_ENCODER_FACTORY_WRAPPER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <jni.h>

#include <memory>
#include <vector>

namespace jni
{
	// Lets WebRTC create its video encoders through a Java
	// VideoEncoderFactory. An encoder the factory implements in Java is run
	// by a VideoEncoderWrapper; a NativeVideoEncoder it returns becomes the
	// built-in encoder for its codec.
	class VideoEncoderFactoryWrapper : public webrtc::VideoEncoderFactory
	{
		public:
			// Asks the Java factory for its supported codecs, which it may
			// throw a Java exception for.
			VideoEncoderFactoryWrapper(JNIEnv * env, jobject factory);
			~VideoEncoderFactoryWrapper() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			class JavaVideoEncoderFactoryClass : public JavaClass
			{
				public:
					explicit JavaVideoEncoderFactoryClass(JNIEnv * env);

					jmethodID getSupportedCodecs;
					jmethodID createEncoder;

					jclass nativeEncoderClass;
					jfieldID nativeEncoderCodecInfo;
			};

		private:
			const JavaGlobalRef<jobject> factory;
			const std::shared_ptr<JavaVideoEncoderFactoryClass> javaClass;
			const std::unique_ptr<webrtc::VideoEncoderFactory> defaultFactory;

			std::vector<webrtc::SdpVideoFormat> supportedFormats;
	};
}

#endif
