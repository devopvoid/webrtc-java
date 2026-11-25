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

#include "JNI_RTCCertificatePEM.h"
#include "JavaBoundary.h"
#include "JavaUtils.h"
#include "rtc/RTCCertificatePEM.h"

#include "rtc_base/rtc_certificate_generator.h"
#include "rtc_base/ssl_identity.h"

#include <cstdint>
#include <optional>

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCCertificatePEM_generateCertificate
(JNIEnv * env, jclass, jint keyType, jlong lifetimeMillis)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		// The order of RTCCertificateKeyType.
		webrtc::KeyParams params = keyType == 1
			? webrtc::KeyParams::RSA()
			: webrtc::KeyParams::ECDSA(webrtc::EC_NIST_P256);

		std::optional<uint64_t> expires;

		if (lifetimeMillis > 0) {
			expires = static_cast<uint64_t>(lifetimeMillis);
		}

		// On the calling thread, which the Java side leaves to the application.
		webrtc::scoped_refptr<webrtc::RTCCertificate> certificate =
			webrtc::RTCCertificateGenerator::GenerateCertificate(params, expires);

		if (!certificate) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "Generating the certificate failed");
			return nullptr;
		}

		return jni::RTCCertificatePEM::toJava(env, *certificate).release();
	});
}
