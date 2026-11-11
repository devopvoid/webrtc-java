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

#include <cstdint>
#include <limits>

namespace jni
{
	namespace RTCCertificatePEM
	{
		jlong ExpiresMillis(const webrtc::SSLCertificate & certificate)
		{
			constexpr int64_t kMillisPerSecond = 1000;
			constexpr int64_t kMax = std::numeric_limits<int64_t>::max();
			constexpr int64_t kMin = std::numeric_limits<int64_t>::min();

			// Seconds relative to the Unix epoch, or -1 when the certificate
			// cannot be parsed. RTCCertificate::Expires() would report that as
			// 0, which is indistinguishable from an actual time.
			const int64_t seconds = certificate.CertificateExpirationTime();

			if (seconds == -1) {
				return kExpiresUnknown;
			}

			// No X.509 time comes near the limits, saturate rather than overflow.
			if (seconds > kMax / kMillisPerSecond) {
				return static_cast<jlong>(kMax);
			}
			if (seconds < kMin / kMillisPerSecond) {
				return static_cast<jlong>(kMin);
			}

			return static_cast<jlong>(seconds * kMillisPerSecond);
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RTCCertificate & certificate)
		{
			return toJava(env, certificate.ToPEM(), ExpiresMillis(certificate.GetSSLCertificate()));
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SSLCertificate & certificate)
		{
			const webrtc::RTCCertificatePEM pem("", certificate.ToPEMString());

			return toJava(env, pem, ExpiresMillis(certificate));
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RTCCertificatePEM & certificate, jlong expires)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCertificatePEMClass>(env);

			// The arguments of the variadic NewObject are not converted to the
			// types of the constructor's signature, the JVM reads the long
			// parameter with va_arg(jlong): it must be passed as a jlong, an int
			// literal there is undefined behavior.
			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, certificate.private_key()).get(),
				JavaString::toJava(env, certificate.certificate()).get(),
				expires
			);

			return JavaLocalRef<jobject>(env, obj);
		}

		webrtc::RTCCertificatePEM toNative(JNIEnv * env, const JavaRef<jobject> & certificate)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCertificatePEMClass>(env);

			JavaObject obj(env, certificate);

			return webrtc::RTCCertificatePEM(
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