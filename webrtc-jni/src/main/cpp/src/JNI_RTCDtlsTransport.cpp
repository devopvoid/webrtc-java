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
#include "api/RegisteredObservers.h"
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
			certificates.add(jni::RTCCertificatePEM::toJava(env, certChain->Get(i)));
		}

		return certificates.listObject().release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

namespace
{
	// Unregisters and frees the observer registered through the Java object.
	// WebRTC is told only while that observer is still the registered one:
	// another Java object for the same transport may have registered its own
	// since, which stays.
	void UnregisterOwnObserver(JNIEnv * env, jobject caller, webrtc::DtlsTransportInterface * transport)
	{
		auto observer = GetHandle<jni::RTCDtlsTransportObserver>(env, caller, "observerHandle");

		if (jni::RegisteredObservers::Remove(transport, observer)) {
			transport->UnregisterObserver();
		}

		ClearNativeObserver<jni::RTCDtlsTransportObserver>(env, caller, "observerHandle");
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_registerObserver
(JNIEnv * env, jobject caller, jobject observer)
{
	try {
		webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
		CHECK_HANDLE(transport);

		auto newObserver = new jni::RTCDtlsTransportObserver(env, jni::JavaGlobalRef<jobject>(env, observer));

		// Registering replaces whatever observer the transport had, also one
		// registered through another Java object, so the one registered
		// through this object before is out of use and can be freed.
		jni::RegisteredObservers::Set(transport, newObserver);

		transport->RegisterObserver(newObserver);

		ReplaceNativeObserver(env, caller, "observerHandle", newObserver);
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

	UnregisterOwnObserver(env, caller, transport);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLE(transport);

	// WebRTC must let go of an observer registered through this object before
	// it is deleted.
	UnregisterOwnObserver(env, caller, transport);

	// The connection, its senders and receivers, and other Java objects keep
	// their own references, so this one need not be the last.
	transport->Release();

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}