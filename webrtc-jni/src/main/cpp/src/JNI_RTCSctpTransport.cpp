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

#include "JNI_RTCSctpTransport.h"
#include "JavaBoundary.h"
#include "NativeHandle.h"
#include "JavaEnums.h"
#include "JavaFactories.h"
#include "JavaPrimitive.h"
#include "JavaRef.h"
#include "JavaUtils.h"

#include "api/sctp_transport_interface.h"

// The transport has no proxy, and most of its methods must run on the network
// thread. Information() is the exception: it calls over to that thread itself,
// so everything here goes through it.

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCSctpTransport_getTransport
(JNIEnv * env, jobject caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		jni::NativeLease<webrtc::SctpTransportInterface> transport(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		webrtc::scoped_refptr<webrtc::DtlsTransportInterface> dtlsTransport = transport->Information().dtls_transport();

		if (!dtlsTransport) {
			return nullptr;
		}

		return jni::JavaFactories::createOwning(env, std::move(dtlsTransport)).release();
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCSctpTransport_getState
(JNIEnv * env, jobject caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		jni::NativeLease<webrtc::SctpTransportInterface> transport(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		return jni::JavaEnums::toJava(env, transport->Information().state()).release();
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCSctpTransport_getMaxMessageSize
(JNIEnv * env, jobject caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		jni::NativeLease<webrtc::SctpTransportInterface> transport(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		std::optional<double> size = transport->Information().MaxMessageSize();

		if (!size) {
			return nullptr;
		}

		return jni::Double::create(env, *size).release();
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCSctpTransport_getMaxChannels
(JNIEnv * env, jobject caller)
{
	return jni::CallFromJava(env, [&]() -> jobject {
		jni::NativeLease<webrtc::SctpTransportInterface> transport(env, caller);
		CHECK_HANDLEV(transport, nullptr);

		std::optional<int> channels = transport->Information().MaxChannels();

		if (!channels) {
			return nullptr;
		}

		return jni::Integer::create(env, *channels).release();
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCSctpTransport_dispose
(JNIEnv * env, jobject caller)
{
	jni::CallFromJava(env, [&]() {
		webrtc::SctpTransportInterface * transport = jni::TakeHandle<webrtc::SctpTransportInterface>(env, caller);

		if (transport == nullptr) {
			return;
		}

		// Runs once no other thread uses the transport through this object any
		// more. The connection and other Java objects keep their own
		// references, so this one need not be the last.
		jni::ReleaseHandle(env, transport, [transport](bool) {
			transport->Release();
		});
	});
}
