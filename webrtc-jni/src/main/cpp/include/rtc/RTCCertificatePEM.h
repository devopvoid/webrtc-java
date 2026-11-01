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

#ifndef JNI_WEBRTC_RTC_CERTIFICATE_PEM_H_
#define JNI_WEBRTC_RTC_CERTIFICATE_PEM_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "rtc_base/rtc_certificate.h"
#include "rtc_base/ssl_certificate.h"

#include <jni.h>

namespace jni
{
	namespace RTCCertificatePEM
	{
		class JavaRTCCertificatePEMClass : public JavaClass
		{
			public:
				explicit JavaRTCCertificatePEMClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
				jfieldID expires;
				jfieldID privateKey;
				jfieldID certificate;
		};

		// The value of RTCCertificatePEM.EXPIRES_UNKNOWN, for a certificate whose
		// expiration time cannot be read.
		constexpr jlong kExpiresUnknown = -1;

		// The expiration time of the certificate in milliseconds relative to the
		// Unix epoch, or kExpiresUnknown.
		jlong ExpiresMillis(const webrtc::SSLCertificate & certificate);

		// A local certificate, with its private key.
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RTCCertificate & certificate);
		// A remote certificate, whose private key is not known.
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SSLCertificate & certificate);
		// `expires` in milliseconds relative to the Unix epoch, or kExpiresUnknown.
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RTCCertificatePEM & certificate, jlong expires);
		webrtc::RTCCertificatePEM toNative(JNIEnv * env, const JavaRef<jobject> & certificate);
	}
}

#endif