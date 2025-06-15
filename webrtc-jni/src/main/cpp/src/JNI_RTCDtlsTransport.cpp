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
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLEV(transport, nullptr);

	auto iceTransport = transport->ice_transport();

	return jni::JavaFactories::create(env, iceTransport.get()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_getState
(JNIEnv * env, jobject caller)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLEV(transport, nullptr);

	auto info = transport->Information();

	return jni::JavaEnums::toJava(env, info.state()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_getRemoteCertificates
(JNIEnv * env, jobject caller)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLEV(transport, nullptr);

	auto info = transport->Information();
	auto certChain = info.remote_ssl_certificates();

	size_t count = certChain->GetSize();

	jni::JavaArrayList certificates(env, count);

	for (size_t i = 0; i < count; i++) {
		const rtc::SSLCertificate & certificate = certChain->Get(i);
		const rtc::RTCCertificatePEM pem("", certificate.ToPEMString());

		certificates.add(jni::RTCCertificatePEM::toJava(env, pem));
	}

	return certificates.listObject().release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_registerObserver
(JNIEnv * env, jobject caller, jobject observer)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLE(transport);

	transport->RegisterObserver(new jni::RTCDtlsTransportObserver(env, jni::JavaGlobalRef<jobject>(env, observer)));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtlsTransport_unregisterObserver
(JNIEnv * env, jobject caller)
{
	webrtc::DtlsTransportInterface * transport = GetHandle<webrtc::DtlsTransportInterface>(env, caller);
	CHECK_HANDLE(transport);

	transport->UnregisterObserver();
}