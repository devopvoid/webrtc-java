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

#include "api/RTCCryptoOptions.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCCryptoOptions
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::CryptoOptions & options)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCryptoOptionsClass>(env);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor);

			ExceptionCheck(env);

			env->SetBooleanField(object, javaClass->enableGcmCryptoSuites, options.srtp.enable_gcm_crypto_suites);
			env->SetBooleanField(object, javaClass->preferGcmCryptoSuites, options.srtp.prefer_gcm_crypto_suites);
			env->SetBooleanField(object, javaClass->enableAes128Sha1_32CryptoCipher,
				options.srtp.enable_aes128_sha1_32_crypto_cipher);
			env->SetBooleanField(object, javaClass->enableAes128Sha1_80CryptoCipher,
				options.srtp.enable_aes128_sha1_80_crypto_cipher);
			env->SetBooleanField(object, javaClass->enableEncryptedRtpHeaderExtensions,
				options.srtp.enable_encrypted_rtp_header_extensions);
			env->SetObjectField(object, javaClass->cryptexPolicy,
				JavaEnums::toJava(env, options.srtp.cryptex_policy).get());

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::CryptoOptions toNative(JNIEnv * env, const JavaRef<jobject> & options)
		{
			const auto javaClass = JavaClasses::get<JavaRTCCryptoOptionsClass>(env);

			JavaObject obj(env, options);

			webrtc::CryptoOptions cryptoOptions;
			cryptoOptions.srtp.enable_gcm_crypto_suites = obj.getBoolean(javaClass->enableGcmCryptoSuites);
			cryptoOptions.srtp.prefer_gcm_crypto_suites = obj.getBoolean(javaClass->preferGcmCryptoSuites);
			cryptoOptions.srtp.enable_aes128_sha1_32_crypto_cipher =
				obj.getBoolean(javaClass->enableAes128Sha1_32CryptoCipher);
			cryptoOptions.srtp.enable_aes128_sha1_80_crypto_cipher =
				obj.getBoolean(javaClass->enableAes128Sha1_80CryptoCipher);
			cryptoOptions.srtp.enable_encrypted_rtp_header_extensions =
				obj.getBoolean(javaClass->enableEncryptedRtpHeaderExtensions);

			JavaLocalRef<jobject> cryptexPolicy = obj.getObject(javaClass->cryptexPolicy);

			if (cryptexPolicy.get() != nullptr) {
				cryptoOptions.srtp.cryptex_policy =
					JavaEnums::toNative<webrtc::CryptoOptions::Srtp::CryptexPolicy>(env, cryptexPolicy);
			}

			return cryptoOptions;
		}

		JavaRTCCryptoOptionsClass::JavaRTCCryptoOptionsClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCCryptoOptions");

			ctor = GetMethod(env, cls, "<init>", "()V");

			enableGcmCryptoSuites = GetFieldID(env, cls, "enableGcmCryptoSuites", "Z");
			preferGcmCryptoSuites = GetFieldID(env, cls, "preferGcmCryptoSuites", "Z");
			enableAes128Sha1_32CryptoCipher = GetFieldID(env, cls, "enableAes128Sha1_32CryptoCipher", "Z");
			enableAes128Sha1_80CryptoCipher = GetFieldID(env, cls, "enableAes128Sha1_80CryptoCipher", "Z");
			enableEncryptedRtpHeaderExtensions = GetFieldID(env, cls, "enableEncryptedRtpHeaderExtensions", "Z");
			cryptexPolicy = GetFieldID(env, cls, "cryptexPolicy", "L" PKG "RTCCryptexPolicy;");
		}
	}
}
