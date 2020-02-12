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

#include "rtc/RTCCertificatePEM.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

#include "rtc_base/ref_count.h"
#include "rtc_base/rtc_certificate_generator.h"

namespace jni
{
	namespace RTCCertificatePEM
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const rtc::RTCCertificatePEM & certificate)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCertificatePEMClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, certificate.private_key()).get(),
				JavaString::toJava(env, certificate.certificate()).get(),
				0
			);

			return JavaLocalRef<jobject>(env, obj);
		}

		rtc::RTCCertificatePEM toNative(JNIEnv * env, const JavaRef<jobject> & certificate)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCertificatePEMClass>(env);

			JavaObject obj(env, certificate);

			return rtc::RTCCertificatePEM(
				JavaString::toNative(env, obj.getString(javaClass->privateKey)),
				JavaString::toNative(env, obj.getString(javaClass->certificate))
			);
		}

		JavaRTCCertificatePEMClass::JavaRTCCertificatePEMClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCCertificatePEM");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG STRING_SIG "J)V");

			expires = GetFieldID(env, cls, "expires", "J");
			privateKey = GetFieldID(env, cls, "privateKey", STRING_SIG);
			certificate = GetFieldID(env, cls, "certificate", STRING_SIG);
		}
	}
}