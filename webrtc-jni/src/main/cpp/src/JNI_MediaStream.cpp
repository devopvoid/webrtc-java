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

#include "JNI_MediaStream.h"
#include "api/WebRTCUtils.h"
#include "JavaError.h"
#include "JavaFactories.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/media_stream_interface.h"
#include "rtc_base/logging.h"

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_MediaStream_id
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamInterface * stream = GetHandle<webrtc::MediaStreamInterface>(env, caller);
	CHECK_HANDLEV(stream, nullptr);

	return jni::JavaString::toJava(env, stream->id());
}

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_media_MediaStream_getAudioTracks
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamInterface * stream = GetHandle<webrtc::MediaStreamInterface>(env, caller);
	CHECK_HANDLEV(stream, nullptr);

	jni::JavaLocalRef<jobjectArray> objectArray;

	try {
		objectArray = jni::createObjectArray(env, stream->GetAudioTracks());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return objectArray;
}

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_media_MediaStream_getVideoTracks
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamInterface * stream = GetHandle<webrtc::MediaStreamInterface>(env, caller);
	CHECK_HANDLEV(stream, nullptr);

	jni::JavaLocalRef<jobjectArray> objectArray;

	try {
		objectArray = jni::createObjectArray(env, stream->GetVideoTracks());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return objectArray;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStream_addTrack
(JNIEnv * env, jobject caller, jobject jTrack)
{
	webrtc::MediaStreamInterface * stream = GetHandle<webrtc::MediaStreamInterface>(env, caller);
	CHECK_HANDLE(stream);

	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, jTrack);
	CHECK_HANDLE(track);

	if (webrtc::AudioTrackInterface * t = dynamic_cast<webrtc::AudioTrackInterface *>(track)) {
		stream->AddTrack(t);
	}
	else if (webrtc::VideoTrackInterface * t = dynamic_cast<webrtc::VideoTrackInterface *>(track)) {
		stream->AddTrack(t);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStream_removeTrack
(JNIEnv * env, jobject caller, jobject jTrack)
{
	webrtc::MediaStreamInterface * stream = GetHandle<webrtc::MediaStreamInterface>(env, caller);
	CHECK_HANDLE(stream);

	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, jTrack);
	CHECK_HANDLE(track);

	if (webrtc::AudioTrackInterface * t = dynamic_cast<webrtc::AudioTrackInterface *>(track)) {
		stream->RemoveTrack(t);
	}
	else if (webrtc::VideoTrackInterface * t = dynamic_cast<webrtc::VideoTrackInterface *>(track)) {
		stream->RemoveTrack(t);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStream_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamInterface * stream = GetHandle<webrtc::MediaStreamInterface>(env, caller);
	CHECK_HANDLE(stream);

	rtc::RefCountReleaseStatus status = stream->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	stream = nullptr;
}