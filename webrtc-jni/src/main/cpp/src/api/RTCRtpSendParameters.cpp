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

#include "api/RTCRtpSendParameters.h"
#include "api/RTCRtpCodecParameters.h"
#include "api/RTCRtpEncodingParameters.h"
#include "api/RTCRtpHeaderExtension.h"
#include "api/RTCRtpParameters.h"
#include "api/RTCRtcpParameters.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpSendParameters
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpParameters & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpSendParametersClass>(env);
			const auto javaParentClass = JavaClasses::get<RTCRtpParameters::JavaRTCRtpParametersClass>(env);

			JavaLocalRef<jstring> transactionId = JavaString::toJava(env, parameters.transaction_id);
			JavaLocalRef<jobject> rtcp = RTCRtcpParameters::toJava(env, parameters.rtcp);
			JavaLocalRef<jobject> encodings = JavaList::toArrayList(env, parameters.encodings, &RTCRtpEncodingParameters::toJava);
			JavaLocalRef<jobject> headerExtensions = JavaList::toArrayList(env, parameters.header_extensions, &RTCRtpHeaderExtension::toJava);
			JavaLocalRef<jobject> codecs = JavaList::toArrayList(env, parameters.codecs, &RTCRtpCodecParameters::toJava);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor);
			env->SetObjectField(object, javaClass->transactionId, transactionId.get());
			env->SetObjectField(object, javaClass->encodings, encodings.get());
			env->SetObjectField(object, javaParentClass->headerExtensions, headerExtensions.get());
			env->SetObjectField(object, javaParentClass->rtcp, rtcp.get());
			env->SetObjectField(object, javaParentClass->codecs, codecs.get());

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtpParameters toNative(JNIEnv * env, const JavaRef<jobject> & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpSendParametersClass>(env);
			const auto javaParentClass = JavaClasses::get<RTCRtpParameters::JavaRTCRtpParametersClass>(env);

			JavaObject obj(env, parameters);

			JavaLocalRef<jstring> transactionId = obj.getString(javaClass->transactionId);
			JavaLocalRef<jobject> encodings = obj.getObject(javaClass->encodings);
			JavaLocalRef<jobject> headerExtensions = obj.getObject(javaParentClass->headerExtensions);
			JavaLocalRef<jobject> rtcp = obj.getObject(javaParentClass->rtcp);
			JavaLocalRef<jobject> codecs = obj.getObject(javaParentClass->codecs);

			webrtc::RtpParameters params;
			params.transaction_id = JavaString::toNative(env, transactionId);

			if (encodings) {
				params.encodings = JavaList::toVector(env, encodings, &RTCRtpEncodingParameters::toNative);
			}
			if (headerExtensions) {
				params.header_extensions = JavaList::toVector(env, headerExtensions, &RTCRtpHeaderExtension::toNative);
			}
			if (rtcp) {
				params.rtcp = RTCRtcpParameters::toNative(env, rtcp);
			}
			if (codecs) {
				params.codecs = JavaList::toVector(env, codecs, &RTCRtpCodecParameters::toNative);
			}

			return params;
		}

		JavaRTCRtpSendParametersClass::JavaRTCRtpSendParametersClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpSendParameters");

			ctor = GetMethod(env, cls, "<init>", "()V");

			transactionId = GetFieldID(env, cls, "transactionId", STRING_SIG);
			encodings = GetFieldID(env, cls, "encodings", LIST_SIG);
		}
	}
}
