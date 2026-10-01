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

#include "media/video/codec/VideoDecoderFactoryWrapper.h"
#include "media/video/codec/DefaultVideoCodecFactories.h"
#include "media/video/codec/JavaLocalFrame.h"
#include "media/video/codec/VideoCodecInfo.h"
#include "media/video/codec/VideoCodecUtils.h"
#include "media/video/codec/VideoDecoderWrapper.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "rtc_base/logging.h"

namespace jni
{
	VideoDecoderFactoryWrapper::VideoDecoderFactoryWrapper(JNIEnv * env, jobject factory) :
		factory(env, factory),
		javaClass(JavaClasses::get<JavaVideoDecoderFactoryClass>(env)),
		defaultFactory(CreateDefaultVideoDecoderFactory())
	{
		JavaLocalRef<jobject> codecs(env, env->CallObjectMethod(factory, javaClass->getSupportedCodecs));

		ExceptionCheck(env);

		supportedFormats = VideoCodecInfo::toNativeList(env, codecs);
	}

	std::vector<webrtc::SdpVideoFormat> VideoDecoderFactoryWrapper::GetSupportedFormats() const
	{
		return supportedFormats;
	}

	std::unique_ptr<webrtc::VideoDecoder> VideoDecoderFactoryWrapper::Create(const webrtc::Environment & environment,
		const webrtc::SdpVideoFormat & format)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return nullptr;
		}

		JavaLocalFrame localFrame(env, 16);

		try {
			JavaLocalRef<jobject> info = VideoCodecInfo::toJava(env, format);
			jobject decoder = env->CallObjectMethod(factory, javaClass->createDecoder, info.get());

			if (ClearCodecException(env, "createDecoder") || decoder == nullptr) {
				RTC_LOG(LS_WARNING) << "Java decoder factory created no decoder for " << format.ToString();

				return nullptr;
			}

			if (env->IsInstanceOf(decoder, javaClass->nativeDecoderClass)) {
				JavaLocalRef<jobject> nativeInfo(env, env->GetObjectField(decoder, javaClass->nativeDecoderCodecInfo));

				return defaultFactory->Create(environment, VideoCodecInfo::toNative(env, nativeInfo));
			}

			return std::make_unique<VideoDecoderWrapper>(env, decoder);
		}
		catch (...) {
			ClearCodecException(env, "createDecoder");

			RTC_LOG(LS_WARNING) << "Java decoder factory failed to create a decoder for " << format.ToString();
		}

		return nullptr;
	}

	VideoDecoderFactoryWrapper::JavaVideoDecoderFactoryClass::JavaVideoDecoderFactoryClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_CODEC"VideoDecoderFactory");

		getSupportedCodecs = GetMethod(env, cls, "getSupportedCodecs", "()" LIST_SIG);
		createDecoder = GetMethod(env, cls, "createDecoder",
			"(L" PKG_CODEC "VideoCodecInfo;)L" PKG_CODEC "VideoDecoder;");

		nativeDecoderClass = FindClass(env, PKG_CODEC"NativeVideoDecoder");
		nativeDecoderCodecInfo = GetFieldID(env, nativeDecoderClass, "codecInfo", "L" PKG_CODEC "VideoCodecInfo;");
	}
}
