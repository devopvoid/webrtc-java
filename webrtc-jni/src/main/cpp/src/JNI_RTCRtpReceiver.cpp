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

#include "JNI_RTCRtpReceiver.h"
#include "api/RTCRtpParameters.h"
#include "api/RTCRtpContributingSource.h"
#include "api/RTCRtpSynchronizationSource.h"
#include "JavaFactories.h"
#include "JavaList.h"
#include "JavaUtils.h"

#include "api/rtp_receiver_interface.h"

#include <algorithm>

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpReceiver_getTrack
(JNIEnv * env, jobject caller)
{
	webrtc::RtpReceiverInterface * receiver = GetHandle<webrtc::RtpReceiverInterface>(env, caller);
	CHECK_HANDLEV(receiver, nullptr);

	rtc::scoped_refptr<webrtc::MediaStreamTrackInterface> track = receiver->track();

	if (webrtc::AudioTrackInterface * t = dynamic_cast<webrtc::AudioTrackInterface *>(track.get())) {
		return jni::JavaFactories::create(env, t).release();
	}
	else if (webrtc::VideoTrackInterface * t = dynamic_cast<webrtc::VideoTrackInterface *>(track.get())) {
		return jni::JavaFactories::create(env, t).release();
	}

	return nullptr;
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpReceiver_getTransport
(JNIEnv * env, jobject caller)
{
	webrtc::RtpReceiverInterface * receiver = GetHandle<webrtc::RtpReceiverInterface>(env, caller);
	CHECK_HANDLEV(receiver, nullptr);

	auto transport = receiver->dtls_transport();

	return jni::JavaFactories::create(env, transport.get()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpReceiver_getParameters
(JNIEnv * env, jobject caller)
{
	webrtc::RtpReceiverInterface * receiver = GetHandle<webrtc::RtpReceiverInterface>(env, caller);
	CHECK_HANDLEV(receiver, nullptr);

	return jni::RTCRtpParameters::toJava(env, receiver->GetParameters()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpReceiver_getContributingSources
(JNIEnv * env, jobject caller)
{
	webrtc::RtpReceiverInterface * receiver = GetHandle<webrtc::RtpReceiverInterface>(env, caller);
	CHECK_HANDLEV(receiver, nullptr);

	std::vector<webrtc::RtpSource> sources = receiver->GetSources();
	std::vector<webrtc::RtpSource> csrc;

	std::copy_if(sources.begin(), sources.end(), std::back_inserter(csrc),
		[](webrtc::RtpSource s) { return s.source_type() == webrtc::RtpSourceType::CSRC; });

	auto list = jni::JavaList::toArrayList(env, csrc, jni::RTCRtpContributingSource::toJava);

	return list.release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpReceiver_getSynchronizationSources
(JNIEnv * env, jobject caller)
{
	webrtc::RtpReceiverInterface * receiver = GetHandle<webrtc::RtpReceiverInterface>(env, caller);
	CHECK_HANDLEV(receiver, nullptr);

	std::vector<webrtc::RtpSource> sources = receiver->GetSources();
	std::vector<webrtc::RtpSource> ssrc;

	std::copy_if(sources.begin(), sources.end(), std::back_inserter(ssrc),
		[](webrtc::RtpSource s) { return s.source_type() == webrtc::RtpSourceType::SSRC; });

	auto list = jni::JavaList::toArrayList(env, ssrc, jni::RTCRtpSynchronizationSource::toJava);

	return list.release();
}