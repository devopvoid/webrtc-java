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
#include "JavaBoundary.h"
#include "NativeHandle.h"
#include "api/ObserverSlot.h"
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
		jni::NativeLease<webrtc::DtlsTransportInterface> transport(env, caller);
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
		jni::NativeLease<webrtc::DtlsTransportInterface> transport(env, caller);
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
		jni::NativeLease<webrtc::DtlsTransportInterface> transport(env, caller);
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
	using DtlsTransportObserverSlot = jni::ObserverSlot<jni::RTCDtlsTransportObserver>;

	// Registering replaces whatever observer the transport had, also one
	// registered through another Java object. WebRTC is told of a removal only
	// while the observer removed is still the registered one: another Java
	// object for the same transport may have registered its own since, which
	// stays.
	DtlsTransportObserverSlot::Apply ApplyTo(webrtc::DtlsTransportInterface * transport)
	{
		return jni::RegisteredObservers::Apply<jni::RTCDtlsTransportObserver>(transport,
			[transport](jni::RTCDtlsTransportObserver * observer) { transport->RegisterObserver(observer); },
			[transport]() { transport->UnregisterObserver(); });
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_registerObserver
(JNIEnv * env, jobject caller, jobject observer)
{
	try {
		jni::NativeLease<webrtc::DtlsTransportInterface> transport(env, caller);
		CHECK_HANDLE(transport);

		auto newObserver = new jni::RTCDtlsTransportObserver(env, jni::JavaGlobalRef<jobject>(env, observer));

		DtlsTransportObserverSlot::Set(env, caller, "observerHandle", newObserver, ApplyTo(transport));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_unregisterObserver
(JNIEnv * env, jobject caller)
{
	try {
		jni::NativeLease<webrtc::DtlsTransportInterface> transport(env, caller);
		CHECK_HANDLE(transport);

		DtlsTransportObserverSlot::Set(env, caller, "observerHandle", nullptr, ApplyTo(transport));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_dispose
(JNIEnv * env, jobject caller)
{
	jni::CallFromJava(env, [&]() {
		webrtc::DtlsTransportInterface * transport = jni::TakeHandle<webrtc::DtlsTransportInterface>(env, caller);

		if (transport == nullptr) {
			return;
		}

		auto slot = DtlsTransportObserverSlot::Take(env, caller, "observerHandle");

		// Runs once no other thread registers through this object any more. WebRTC
		// must let go of an observer registered through this object before it is
		// deleted with the slot. The connection, its senders and receivers, and
		// other Java objects keep their own references, so this one need not be
		// the last.
		jni::ReleaseHandle(env, transport, [transport, slot](bool) {
			if (slot && jni::RegisteredObservers::Remove(transport, slot->applied())) {
				transport->UnregisterObserver();
			}

			transport->Release();
		});
	});
}