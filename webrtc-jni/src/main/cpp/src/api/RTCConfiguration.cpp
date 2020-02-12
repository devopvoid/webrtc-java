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

#include "api/RTCConfiguration.h"
#include "api/RTCIceServer.h"
#include "rtc/RTCCertificatePEM.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaRef.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCConfiguration
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::PeerConnectionInterface::RTCConfiguration & nativeType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCConfigurationClass>(env);

			auto certificates = nativeType.certificates;

			auto serverList = JavaList::toArrayList(env, nativeType.servers, &RTCIceServer::toJava);
			JavaArrayList certificateList(env, certificates.size());

			for (auto & certificate : certificates) {
				certificateList.add(jni::RTCCertificatePEM::toJava(env, certificate->ToPEM()));
			}

			auto type = JavaEnums::toJava(env, nativeType.type);
			auto bundlePolicy = JavaEnums::toJava(env, nativeType.bundle_policy);
			auto rtcpMuxPolicy = JavaEnums::toJava(env, nativeType.rtcp_mux_policy);

			jobject config = env->NewObject(javaClass->cls, javaClass->ctor);

			env->SetObjectField(config, javaClass->iceServers, serverList.get());
			env->SetObjectField(config, javaClass->iceTransportPolicy, type.get());
			env->SetObjectField(config, javaClass->bundlePolicy, bundlePolicy.get());
			env->SetObjectField(config, javaClass->rtcpMuxPolicy, rtcpMuxPolicy.get());
			env->SetObjectField(config, javaClass->certificates, certificateList.listObject());

			return JavaLocalRef<jobject>(env, config);
		}

		webrtc::PeerConnectionInterface::RTCConfiguration toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCConfigurationClass>(env);

			JavaObject obj(env, javaType);

			JavaLocalRef<jobject> is = obj.getObject(javaClass->iceServers);
			JavaLocalRef<jobject> tp = obj.getObject(javaClass->iceTransportPolicy);
			JavaLocalRef<jobject> bp = obj.getObject(javaClass->bundlePolicy);
			JavaLocalRef<jobject> mp = obj.getObject(javaClass->rtcpMuxPolicy);
			JavaLocalRef<jobject> cr = obj.getObject(javaClass->certificates);

			webrtc::PeerConnectionInterface::RTCConfiguration configuration;

			configuration.servers = JavaList::toVector(env, is, &RTCIceServer::toNative);
			configuration.sdp_semantics = webrtc::SdpSemantics::kUnifiedPlan;
			configuration.enable_dtls_srtp = true;
			configuration.type = JavaEnums::toNative<webrtc::PeerConnectionInterface::IceTransportsType>(env, tp);
			configuration.bundle_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::BundlePolicy>(env, bp);
			configuration.rtcp_mux_policy = JavaEnums::toNative<webrtc::PeerConnectionInterface::RtcpMuxPolicy>(env, mp);
			
			for (auto & item : JavaIterable(env, cr)) {
				auto certificate = rtc::RTCCertificate::FromPEM(jni::RTCCertificatePEM::toNative(env, item));

				RTC_CHECK(certificate != nullptr) << "Supplied certificate is malformed";

				if (certificate != nullptr) {
					configuration.certificates.push_back(certificate);
				}
			}

			return configuration;
		}

		JavaRTCConfigurationClass::JavaRTCConfigurationClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCConfiguration");

			ctor = GetMethod(env, cls, "<init>", "()V");

			iceServers = GetFieldID(env, cls, "iceServers", LIST_SIG);
			iceTransportPolicy = GetFieldID(env, cls, "iceTransportPolicy", "L" PKG "RTCIceTransportPolicy;");
			bundlePolicy = GetFieldID(env, cls, "bundlePolicy", "L" PKG "RTCBundlePolicy;");
			rtcpMuxPolicy = GetFieldID(env, cls, "rtcpMuxPolicy", "L" PKG "RTCRtcpMuxPolicy;");
			certificates = GetFieldID(env, cls, "certificates", LIST_SIG);
		}
	}
}