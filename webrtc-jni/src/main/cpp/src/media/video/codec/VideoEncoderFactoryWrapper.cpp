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

#include "media/video/codec/VideoEncoderFactoryWrapper.h"
#include "media/video/codec/DefaultVideoCodecFactories.h"
#include "media/video/codec/JavaLocalFrame.h"
#include "media/video/codec/VideoCodecInfo.h"
#include "media/video/codec/VideoCodecUtils.h"
#include "media/video/codec/VideoEncoderWrapper.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "rtc_base/logging.h"

namespace jni
{
	VideoEncoderFactoryWrapper::VideoEncoderFactoryWrapper(JNIEnv * env, jobject factory) :
		factory(env, factory),
		javaClass(JavaClasses::get<JavaVideoEncoderFactoryClass>(env)),
		defaultFactory(CreateDefaultVideoEncoderFactory())
	{
		JavaLocalRef<jobject> codecs(env, env->CallObjectMethod(factory, javaClass->getSupportedCodecs));

		ExceptionCheck(env);

		supportedFormats = VideoCodecInfo::toNativeList(env, codecs);
	}

	std::vector<webrtc::SdpVideoFormat> VideoEncoderFactoryWrapper::GetSupportedFormats() const
	{
		return supportedFormats;
	}

	std::unique_ptr<webrtc::VideoEncoder> VideoEncoderFactoryWrapper::Create(const webrtc::Environment & environment,
		const webrtc::SdpVideoFormat & format)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return nullptr;
		}

		JavaLocalFrame localFrame(env, 16);

		try {
			JavaLocalRef<jobject> info = VideoCodecInfo::toJava(env, format);
			jobject encoder = env->CallObjectMethod(factory, javaClass->createEncoder, info.get());

			if (ClearCodecException(env, "createEncoder") || encoder == nullptr) {
				RTC_LOG(LS_WARNING) << "Java encoder factory created no encoder for " << format.ToString();

				return nullptr;
			}

			if (env->IsInstanceOf(encoder, javaClass->nativeEncoderClass)) {
				JavaLocalRef<jobject> nativeInfo(env, env->GetObjectField(encoder, javaClass->nativeEncoderCodecInfo));

				return defaultFactory->Create(environment, VideoCodecInfo::toNative(env, nativeInfo));
			}

			return std::make_unique<VideoEncoderWrapper>(env, encoder, format);
		}
		catch (...) {
			ClearCodecException(env, "createEncoder");

			RTC_LOG(LS_WARNING) << "Java encoder factory failed to create an encoder for " << format.ToString();
		}

		return nullptr;
	}

	VideoEncoderFactoryWrapper::JavaVideoEncoderFactoryClass::JavaVideoEncoderFactoryClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_CODEC"VideoEncoderFactory");

		getSupportedCodecs = GetMethod(env, cls, "getSupportedCodecs", "()" LIST_SIG);
		createEncoder = GetMethod(env, cls, "createEncoder",
			"(L" PKG_CODEC "VideoCodecInfo;)L" PKG_CODEC "VideoEncoder;");

		nativeEncoderClass = FindClass(env, PKG_CODEC"NativeVideoEncoder");
		nativeEncoderCodecInfo = GetFieldID(env, nativeEncoderClass, "codecInfo", "L" PKG_CODEC "VideoCodecInfo;");
	}
}
