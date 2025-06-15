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

#include "JNI_RTCRtpSender.h"
#include "api/RTCRtpSendParameters.h"
#include <api/WebRTCUtils.h>
#include "JavaFactories.h"
#include "JavaList.h"
#include "JavaRef.h"
#include "JavaRuntimeException.h"
#include "JavaUtils.h"

#include "api/rtp_sender_interface.h"

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getTrack
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	rtc::scoped_refptr<webrtc::MediaStreamTrackInterface> track = sender->track();

	if (webrtc::AudioTrackInterface * t = dynamic_cast<webrtc::AudioTrackInterface *>(track.get())) {
		return jni::JavaFactories::create(env, t).release();
	}
	else if (webrtc::VideoTrackInterface * t = dynamic_cast<webrtc::VideoTrackInterface *>(track.get())) {
		return jni::JavaFactories::create(env, t).release();
	}

	return nullptr;
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getTransport
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	auto transport = sender->dtls_transport();

	return jni::JavaFactories::create(env, transport.get()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_replaceTrack
(JNIEnv * env, jobject caller, jobject jTrack)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	webrtc::MediaStreamTrackInterface * track = jTrack == nullptr
		? nullptr
		: GetHandle<webrtc::MediaStreamTrackInterface>(env, jTrack);

	if (!sender->SetTrack(track)) {
		env->Throw(jni::JavaRuntimeException(env, "Set track failed"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_setParameters
(JNIEnv * env, jobject caller, jobject jParams)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	webrtc::RtpParameters rtp_parameters = jni::RTCRtpSendParameters::toNative(env, jni::JavaLocalRef<jobject>(env, jParams));
	webrtc::RTCError result = sender->SetParameters(rtp_parameters);

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, jni::RTCErrorToString(result).c_str()));
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getParameters
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	return jni::RTCRtpSendParameters::toJava(env, sender->GetParameters()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_setStreams
(JNIEnv * env, jobject caller, jobject streamIdList)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	std::vector<std::string> streamIDs = jni::JavaList::toStringVector(env, jni::JavaLocalRef<jobject>(env, streamIdList));

	sender->SetStreams(streamIDs);
}