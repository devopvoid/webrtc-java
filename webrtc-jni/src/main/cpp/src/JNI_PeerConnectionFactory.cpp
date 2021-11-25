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

#include "JNI_PeerConnectionFactory.h"
#include "api/AudioOptions.h"
#include "api/CreateSessionDescriptionObserver.h"
#include "api/PeerConnectionObserver.h"
#include "api/RTCConfiguration.h"
#include "api/RTCRtpCapabilities.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaFactories.h"
#include "JavaNullPointerException.h"
#include "JavaRuntimeException.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/create_peerconnection_factory.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/video_codecs/builtin_video_decoder_factory.h"
#include "api/video_codecs/builtin_video_encoder_factory.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_initialize
(JNIEnv * env, jobject caller, jobject audioModule, jobject audioProcessing)
{
	webrtc::AudioDeviceModule * audioDevModule = (audioModule != nullptr)
		? GetHandle<webrtc::AudioDeviceModule>(env, audioModule)
		: nullptr;

	try {
		auto networkThread = rtc::Thread::CreateWithSocketServer();
		auto signalingThread = rtc::Thread::Create();
		auto workerThread = rtc::Thread::Create();

		if (!networkThread->Start()) {
			throw jni::Exception("Start network thread failed");
		}
		if (!signalingThread->Start()) {
			throw jni::Exception("Start signaling thread failed");
		}
		if (!workerThread->Start()) {
			throw jni::Exception("Start worker thread failed");
		}

		webrtc::AudioProcessing * processing = (audioProcessing != nullptr)
			? GetHandle<webrtc::AudioProcessing>(env, audioProcessing)
			: nullptr;
		rtc::scoped_refptr<webrtc::AudioProcessing> apm(processing);

		auto factory = webrtc::CreatePeerConnectionFactory(
			networkThread.get(),
			workerThread.get(),
			signalingThread.get(),
			audioDevModule,
			webrtc::CreateBuiltinAudioEncoderFactory(),
			webrtc::CreateBuiltinAudioDecoderFactory(),
			webrtc::CreateBuiltinVideoEncoderFactory(),
			webrtc::CreateBuiltinVideoDecoderFactory(),
			nullptr,
			apm);

		if (factory != nullptr) {
			SetHandle(env, caller, factory.release());
			SetHandle(env, caller, "networkThreadHandle", networkThread.release());
			SetHandle(env, caller, "signalingThreadHandle", signalingThread.release());
			SetHandle(env, caller, "workerThreadHandle", workerThread.release());
		}
		else {
			throw jni::Exception("Create PeerConnectionFactory failed");
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLE(factory);

	rtc::Thread * networkThread = GetHandle<rtc::Thread>(env, caller, "networkThreadHandle");
	rtc::Thread * signalingThread = GetHandle<rtc::Thread>(env, caller, "signalingThreadHandle");
	rtc::Thread * workerThread = GetHandle<rtc::Thread>(env, caller, "workerThreadHandle");

	rtc::RefCountReleaseStatus status = factory->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		env->Throw(jni::JavaError(env, "Native object was not deleted. A reference is still around somewhere."));
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	factory = nullptr;

	try {
		if (networkThread) {
			networkThread->Stop();
			delete networkThread;
		}
		if (signalingThread) {
			signalingThread->Stop();
			delete signalingThread;
		}
		if (workerThread) {
			workerThread->Stop();
			delete workerThread;
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_createAudioSource
(JNIEnv * env, jobject caller, jobject jAudioOptions)
{
	if (jAudioOptions == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "AudioOptions is null"));
		return nullptr;
	}

	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLEV(factory, nullptr);

	auto audioOptions = jni::AudioOptions::toNative(env, jni::JavaLocalRef<jobject>(env, jAudioOptions));

	rtc::scoped_refptr<webrtc::AudioSourceInterface> audioSource = factory->CreateAudioSource(audioOptions);

	if (audioSource == nullptr) {
		env->Throw(jni::JavaError(env, "Create audio-track source failed"));
		return nullptr;
	}

	return jni::JavaFactories::create(env, audioSource.release()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_createAudioTrack
(JNIEnv * env, jobject caller, jstring jlabel, jobject jsource)
{
	if (jlabel == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "Audio track label is null"));
		return nullptr;
	}
	if (jsource == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "AudioTrackSource is null"));
		return nullptr;
	}

	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLEV(factory, nullptr);

	webrtc::AudioSourceInterface * source = GetHandle<webrtc::AudioSourceInterface>(env, jsource);
	CHECK_HANDLEV(source, nullptr);

	std::string label = jni::JavaString::toNative(env, jni::JavaLocalRef<jstring>(env, jlabel));

	rtc::scoped_refptr<webrtc::AudioTrackInterface> audioTrack = factory->CreateAudioTrack(label, source);

	return jni::JavaFactories::create(env, audioTrack.release()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_createVideoTrack
(JNIEnv * env, jobject caller, jstring jlabel, jobject jsource)
{
	if (jlabel == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "Video track label is null"));
		return nullptr;
	}
	if (jsource == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "VideoTrackSource is null"));
		return nullptr;
	}

	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLEV(factory, nullptr);

	webrtc::VideoTrackSourceInterface * source = GetHandle<webrtc::VideoTrackSourceInterface>(env, jsource);
	CHECK_HANDLEV(source, nullptr);

	std::string label = jni::JavaString::toNative(env, jni::JavaLocalRef<jstring>(env, jlabel));

	rtc::scoped_refptr<webrtc::VideoTrackInterface> videoTrack = factory->CreateVideoTrack(label, source);

	return jni::JavaFactories::create(env, videoTrack.release()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_createPeerConnection
(JNIEnv * env, jobject caller, jobject jConfig, jobject jobserver)
{
	if (jConfig == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCConfiguration is null"));
		return nullptr;
	}
	if (jobserver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "PeerConnectionObserver is null"));
		return nullptr;
	}

	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLEV(factory, nullptr);

	webrtc::PeerConnectionInterface::RTCConfiguration configuration = jni::RTCConfiguration::toNative(env, jni::JavaLocalRef<jobject>(env, jConfig));
	webrtc::PeerConnectionObserver * observer = new jni::PeerConnectionObserver(env, jni::JavaGlobalRef<jobject>(env, jobserver));
	webrtc::PeerConnectionDependencies dependencies(observer);

	auto result = factory->CreatePeerConnectionOrError(configuration, std::move(dependencies));

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, "Create PeerConnection failed: %s %s",
			ToString(result.error().type()), result.error().message()));

		return nullptr;
	}

	auto pc = result.MoveValue();

	if (pc != nullptr) {
		auto javaPeerConnection = jni::JavaFactories::create(env, pc.release());

		SetHandle(env, javaPeerConnection.get(), "observerHandle", observer);

		return javaPeerConnection.release();
	}

	return nullptr;
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_getRtpReceiverCapabilities
(JNIEnv * env, jobject caller, jobject mediaType)
{
	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLEV(factory, nullptr);

	auto type = jni::JavaEnums::toNative<cricket::MediaType>(env, mediaType);
	auto capabilities = factory->GetRtpReceiverCapabilities(type);

	return jni::RTCRtpCapabilities::toJava(env, capabilities).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_PeerConnectionFactory_getRtpSenderCapabilities
(JNIEnv * env, jobject caller, jobject mediaType)
{
	webrtc::PeerConnectionFactoryInterface * factory = GetHandle<webrtc::PeerConnectionFactoryInterface>(env, caller);
	CHECK_HANDLEV(factory, nullptr);

	auto type = jni::JavaEnums::toNative<cricket::MediaType>(env, mediaType);
	auto capabilities = factory->GetRtpSenderCapabilities(type);

	return jni::RTCRtpCapabilities::toJava(env, capabilities).release();
}