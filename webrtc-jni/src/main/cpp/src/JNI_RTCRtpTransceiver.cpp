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

#include "JNI_RTCRtpTransceiver.h"
#include "api/RTCRtpCodecCapability.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaFactories.h"
#include "JavaList.h"
#include "JavaRef.h"
#include "JavaRuntimeException.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "WebRTCUtils.h"

#include "api/rtp_transceiver_interface.h"

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_getMid
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, nullptr);

	return jni::JavaString::toJava(env, transceiver->mid().value_or("")).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_getSender
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, nullptr);

	webrtc::scoped_refptr<webrtc::RtpSenderInterface> sender = transceiver->sender();

	// Transfer the reference this local scoped_refptr holds into the Java
	// wrapper, which owns and disposes it -- otherwise the wrapper would be
	// left with a pointer nothing on the Java/JNI side keeps alive.
	return jni::JavaFactories::create(env, sender.release()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_getReceiver
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, nullptr);

	webrtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver = transceiver->receiver();

	// Transfer the reference this local scoped_refptr holds into the Java
	// wrapper, which owns and disposes it -- otherwise the wrapper would be
	// left with a pointer nothing on the Java/JNI side keeps alive.
	return jni::JavaFactories::create(env, receiver.release()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_getDirection
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, nullptr);

	return jni::JavaEnums::toJava(env, transceiver->direction()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_setDirection
(JNIEnv * env, jobject caller, jobject jDirection)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLE(transceiver);

	webrtc::RTCError result = transceiver->SetDirectionWithError(jni::JavaEnums::toNative<webrtc::RtpTransceiverDirection>(env, jDirection));

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, "Set direction failed: %s %s",
			ToString(result.type()), result.message()));
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_getCurrentDirection
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, nullptr);

	auto directionOpt = transceiver->current_direction();

	if (!directionOpt.has_value()) {
		return nullptr;
	}

	return jni::JavaEnums::toJava(env, directionOpt.value()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_stop
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLE(transceiver);

	webrtc::RTCError result = transceiver->StopStandard();

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, "Stop transceiver failed: %s %s",
			ToString(result.type()), result.message()));
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_stopped
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, true);

	return static_cast<jboolean>(transceiver->stopped());
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_getCodecPreferences
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLEV(transceiver, nullptr);

	auto capabilities = jni::JavaList::toArrayList(env, transceiver->codec_preferences(), jni::RTCRtpCodecCapability::toJava);

	return capabilities.get();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_setCodecPreferences
(JNIEnv * env, jobject caller, jobject jPreferences)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLE(transceiver);

	try {
		auto ref = jni::JavaLocalRef<jobject>(env, jPreferences);
		auto preferences = jni::JavaList::toVector(env, ref, &jni::RTCRtpCodecCapability::toNative);

		webrtc::RTCError result = transceiver->SetCodecPreferences(preferences);

		if (!result.ok()) {
			env->Throw(jni::JavaError(env, "Set codec preferences failed: %s", jni::RTCErrorToString(result).c_str()));
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpTransceiver_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::RtpTransceiverInterface * transceiver = GetHandle<webrtc::RtpTransceiverInterface>(env, caller);
	CHECK_HANDLE(transceiver);

	// Unlike e.g. MediaStreamTrack, an RTCRtpTransceiver is not exclusively
	// owned by one Java wrapper: the owning PeerConnection keeps its own
	// reference, and other Java wrappers may have been obtained via separate
	// getTransceivers() calls. Dropping our reference here is expected to
	// leave others around, so it is not reported as an error.
	transceiver->Release();

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}