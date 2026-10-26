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

#include "JNI_RTCDtlsTransport.h"
#include "api/RTCDtlsTransportObserver.h"
#include "rtc/RTCCertificatePEM.h"
#include "JavaArrayList.h"
#include "JavaEnums.h"
#include "JavaFactories.h"
#include "JavaRef.h"
#include "JavaUtils.h"

#include "api/dtls_transport_interface.h"

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_getIceTransport
(JNIEnv * env, jobject caller)
{
	try {
		webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		auto iceTransport = transport->ice_transport();

		return jni::JavaFactories::create(env, iceTransport.get()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_getState
(JNIEnv * env, jobject caller)
{
	try {
		webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		auto info = transport->Information();

		return jni::JavaEnums::toJava(env, info.state()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_getRemoteCertificates
(JNIEnv * env, jobject caller)
{
	try {
		webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		auto info = transport->Information();
		auto certChain = info.remote_ssl_certificates();

		// There is no chain before the handshake has received one.
		size_t count = certChain != nullptr ? certChain->GetSize() : 0;

		jni::JavaArrayList certificates(env, count);

		for (size_t i = 0; i < count; i++) {
			const webrtc::SSLCertificate & certificate = certChain->Get(i);
			const webrtc::RTCCertificatePEM pem("", certificate.ToPEMString());

			certificates.add(jni::RTCCertificatePEM::toJava(env, pem));
		}

		return certificates.listObject().release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_registerObserver
(JNIEnv * env, jobject caller, jobject observer)
{
	try {
		webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
		CHECK_HANDLE(transport);

		// Unregister and free a previously registered observer before replacing
		// it, otherwise it would leak along with its JNI global reference.
		transport->UnregisterObserver();

		auto newObserver = new jni::RTCDtlsTransportObserver(env, jni::JavaGlobalRef<jobject>(env, observer));
		ReplaceNativeObserver(env, caller, "observerHandle", newObserver);

		transport->RegisterObserver(newObserver);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_unregisterObserver
(JNIEnv * env, jobject caller)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLE(transport);

	transport->UnregisterObserver();

	ClearNativeObserver<jni::RTCDtlsTransportObserver>(env, caller, "observerHandle");
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLE(transport);

	// WebRTC must let go of an observer registered through this object before
	// it is deleted.
	if (GetHandle<jni::RTCDtlsTransportObserver>(env, caller, "observerHandle") != nullptr) {
		transport->UnregisterObserver();

		ClearNativeObserver<jni::RTCDtlsTransportObserver>(env, caller, "observerHandle");
	}

	// The connection, its senders and receivers, and other Java objects keep
	// their own references, so this one need not be the last.
	transport->Release();

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}