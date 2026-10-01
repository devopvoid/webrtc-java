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

#include "media/video/codec/VideoCodecUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

namespace jni
{
	bool ClearCodecException(JNIEnv * env, const char * context)
	{
		if (!env->ExceptionCheck()) {
			return false;
		}

		// Cleared before logging, since a Java log sink cannot be called
		// with an exception pending.
		env->ExceptionDescribe();
		env->ExceptionClear();

		RTC_LOG(LS_WARNING) << "Java video codec threw in " << context;

		return true;
	}

	int32_t ToNativeCodecStatus(JNIEnv * env, jobject status, const char * context)
	{
		if (ClearCodecException(env, context)) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}
		if (status == nullptr) {
			RTC_LOG(LS_WARNING) << "Java video codec returned no status from " << context;

			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		const auto javaClass = JavaClasses::get<JavaVideoCodecStatusClass>(env);

		jint number = env->CallIntMethod(status, javaClass->getNumber);

		if (ClearCodecException(env, context)) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		return static_cast<int32_t>(number);
	}

	JavaVideoCodecStatusClass::JavaVideoCodecStatusClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG_CODEC"VideoCodecStatus");

		getNumber = GetMethod(env, cls, "getNumber", "()I");
	}
}
