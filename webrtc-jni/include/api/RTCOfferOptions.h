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

#ifndef JNI_WEBRTC_API_RTC_OFFER_OPTIONS_H_
#define JNI_WEBRTC_API_RTC_OFFER_OPTIONS_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/peer_connection_interface.h"

#include <jni.h>

namespace jni
{
	namespace RTCOfferOptions
	{
		class JavaRTCOfferOptionsClass : public JavaClass
		{
			public:
				explicit JavaRTCOfferOptionsClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
				jfieldID vad;
				jfieldID iceRestart;
		};

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::PeerConnectionInterface::RTCOfferAnswerOptions & nativeType);
		webrtc::PeerConnectionInterface::RTCOfferAnswerOptions toNative(JNIEnv * env, const JavaRef<jobject> & javaType);
	}
}

#endif