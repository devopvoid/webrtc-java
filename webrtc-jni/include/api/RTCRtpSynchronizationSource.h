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

#ifndef JNI_WEBRTC_API_RTC_RTP_SYNCHRONIZATION_SOURCE_H_
#define JNI_WEBRTC_API_RTC_RTP_SYNCHRONIZATION_SOURCE_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/transport/rtp/rtp_source.h"

#include <jni.h>

namespace jni
{
	namespace RTCRtpSynchronizationSource
	{
		class JavaRTCRtpSynchronizationSourceClass : public JavaClass
		{
			public:
				explicit JavaRTCRtpSynchronizationSourceClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
				jfieldID voiceActivityFlag;
		};

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpSource & source);
		webrtc::RtpSource toNative(JNIEnv * env, const JavaRef<jobject> & source);
	}
}

#endif