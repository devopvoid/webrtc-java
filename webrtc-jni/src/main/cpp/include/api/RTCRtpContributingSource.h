/*
 * Copyright 2019 Alex Andres
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

#ifndef JNI_WEBRTC_API_RTC_RTP_CONTRIBUTING_SOURCE_H_
#define JNI_WEBRTC_API_RTC_RTP_CONTRIBUTING_SOURCE_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/transport/rtp/rtp_source.h"

#include <jni.h>

#include <cstdint>
#include <optional>

namespace jni
{
	namespace RTCRtpContributingSource
	{
		class JavaRTCRtpContributingSourceClass : public JavaClass
		{
			public:
				explicit JavaRTCRtpContributingSourceClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
		};

		// Converts an RTP audio level in -dBov to the linear value between 0 and
		// 1 of the Java classes. A source without a level reads as silence.
		jdouble toLinearAudioLevel(const std::optional<uint8_t> & audioLevel);

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpSource & source);
	}
}

#endif