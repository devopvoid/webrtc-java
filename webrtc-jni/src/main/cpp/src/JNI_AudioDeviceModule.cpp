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

#include "JNI_AudioDeviceModule.h"
#include "Exception.h"
#include "JavaArrayList.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaObject.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "media/audio/AudioDevice.h"
#include "media/audio/AudioTransportSink.h"
#include "media/audio/AudioTransportSource.h"

#include "api/scoped_refptr.h"
#include "api/task_queue/default_task_queue_factory.h"
#include "modules/audio_device/include/audio_device.h"
#include "rtc_base/logging.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_initPlayout
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->InitPlayout() != 0) {
		env->Throw(jni::JavaError(env, "Init playout failed"));
		return;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_stopPlayout
(JNIEnv* env, jobject caller)
{
	webrtc::AudioDeviceModule* audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->StopPlayout() != 0) {
		env->Throw(jni::JavaError(env, "Stop playout failed"));
		return;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_startPlayout
(JNIEnv* env, jobject caller)
{
	webrtc::AudioDeviceModule* audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->StartPlayout() != 0) {
		env->Throw(jni::JavaError(env, "Start playout failed"));
		return;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_initRecording
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->InitRecording() != 0) {
		env->Throw(jni::JavaError(env, "Init recording failed"));
		return;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_stopRecording
(JNIEnv* env, jobject caller)
{
	webrtc::AudioDeviceModule* audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->StopRecording() != 0) {
		env->Throw(jni::JavaError(env, "Stop recording failed"));
		return;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_startRecording
(JNIEnv* env, jobject caller)
{
	webrtc::AudioDeviceModule* audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->StartRecording() != 0) {
		env->Throw(jni::JavaError(env, "Start recording failed"));
		return;
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getPlayoutDevices
(JNIEnv* env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, nullptr);

	char name[webrtc::kAdmMaxDeviceNameSize];
	char guid[webrtc::kAdmMaxGuidSize];

	int16_t deviceCount = audioModule->PlayoutDevices();

	jni::JavaArrayList deviceList(env, deviceCount);

	for (int i = 0; i < deviceCount; ++i) {
		if (audioModule->PlayoutDeviceName(i, name, guid) == 0) {
			auto device = std::make_shared<jni::avdev::AudioDevice>(name, guid);

			deviceList.add(jni::AudioDevice::toJavaAudioDevice(env, device));
		}
	}

	return deviceList.listObject().release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getRecordingDevices
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, nullptr);

	char name[webrtc::kAdmMaxDeviceNameSize];
	char guid[webrtc::kAdmMaxGuidSize];

	int16_t deviceCount = audioModule->RecordingDevices();

	jni::JavaArrayList deviceList(env, deviceCount);

	for (int i = 0; i < deviceCount; ++i) {
		if (audioModule->RecordingDeviceName(i, name, guid) == 0) {
			auto device = std::make_shared<jni::avdev::AudioDevice>(name, guid);

			deviceList.add(jni::AudioDevice::toJavaAudioDevice(env, device));
		}
	}

	return deviceList.listObject().release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_setPlayoutDevice
(JNIEnv * env, jobject caller, jobject device)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (!device) {
		env->Throw(jni::JavaNullPointerException(env, "AudioDevice is null"));
		return;
	}

	jni::JavaObject obj(env, jni::JavaLocalRef<jobject>(env, device));

	const auto javaClass = jni::JavaClasses::get<jni::AudioDevice::JavaAudioDeviceClass>(env);
	const std::string devGuid = jni::JavaString::toNative(env, obj.getString(javaClass->descriptor));

	uint16_t index = 0;
	int16_t deviceCount = audioModule->PlayoutDevices();

	char name[webrtc::kAdmMaxDeviceNameSize];
	char guid[webrtc::kAdmMaxGuidSize];

	for (int i = 0; i < deviceCount; ++i) {
		if ((audioModule->PlayoutDeviceName(i, name, guid) == 0) && devGuid == std::string(guid)) {
			index = i;
			break;
		}
	}

	if (audioModule->SetPlayoutDevice(index) != 0) {
		env->Throw(jni::JavaError(env, "Set playout device failed"));
		return;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_setRecordingDevice
(JNIEnv * env, jobject caller, jobject device)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (!device) {
		env->Throw(jni::JavaNullPointerException(env, "AudioDevice is null"));
		return;
	}

	jni::JavaObject obj(env, jni::JavaLocalRef<jobject>(env, device));
	
	const auto javaClass = jni::JavaClasses::get<jni::AudioDevice::JavaAudioDeviceClass>(env);
	const std::string devGuid = jni::JavaString::toNative(env, obj.getString(javaClass->descriptor));

	uint16_t index = 0;
	int16_t deviceCount = audioModule->RecordingDevices();

	char name[webrtc::kAdmMaxDeviceNameSize];
	char guid[webrtc::kAdmMaxGuidSize];

	for (int i = 0; i < deviceCount; ++i) {
		if ((audioModule->RecordingDeviceName(i, name, guid) == 0) && devGuid == std::string(guid)) {
			index = i;
			break;
		}
	}

	if (audioModule->SetRecordingDevice(index) != 0) {
		env->Throw(jni::JavaError(env, "Set recording device failed"));
		return;
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_isSpeakerMuted
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, true);

	bool mute = true;

	audioModule->SpeakerMute(&mute);

	return mute;
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_isMicrophoneMuted
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, true);

	bool mute = true;

	audioModule->MicrophoneMute(&mute);

	return mute;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getSpeakerVolume
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	uint32_t volume = 0;

	if (audioModule->SpeakerVolume(&volume) != 0) {
		env->Throw(jni::JavaError(env, "Get speaker volume failed"));
	}

	return volume;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getMaxSpeakerVolume
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	uint32_t volume = 0;

	if (audioModule->MaxSpeakerVolume(&volume) != 0) {
		env->Throw(jni::JavaError(env, "Get max speaker volume failed"));
	}

	return volume;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getMinSpeakerVolume
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	uint32_t volume = 0;

	if (audioModule->MinSpeakerVolume(&volume) != 0) {
		env->Throw(jni::JavaError(env, "Get min speaker volume failed"));
	}

	return volume;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getMicrophoneVolume
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	uint32_t volume = 0;

	if (audioModule->MicrophoneVolume(&volume) != 0) {
		env->Throw(jni::JavaError(env, "Get microphone volume failed"));
	}

	return volume;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getMaxMicrophoneVolume
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	uint32_t volume = 0;

	if (audioModule->MaxMicrophoneVolume(&volume) != 0) {
		env->Throw(jni::JavaError(env, "Get max microphone volume failed"));
	}

	return volume;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_getMinMicrophoneVolume
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	uint32_t volume = 0;

	if (audioModule->MinMicrophoneVolume(&volume) != 0) {
		env->Throw(jni::JavaError(env, "Get min microphone volume failed"));
	}

	return volume;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_setSpeakerVolume
(JNIEnv * env, jobject caller, jint volume)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->SetSpeakerVolume(static_cast<uint32_t>(volume)) != 0) {
		env->Throw(jni::JavaError(env, "Set speaker volume failed"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_setSpeakerMute
(JNIEnv * env, jobject caller, jboolean mute)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->SetSpeakerMute(mute) != 0) {
		env->Throw(jni::JavaError(env, "Set speaker mute failed"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_setMicrophoneVolume
(JNIEnv * env, jobject caller, jint volume)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->SetMicrophoneVolume(static_cast<uint32_t>(volume)) != 0) {
		env->Throw(jni::JavaError(env, "Set microphone volume failed"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_setMicrophoneMute
(JNIEnv * env, jobject caller, jboolean mute)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->SetMicrophoneMute(mute) != 0) {
		env->Throw(jni::JavaError(env, "Set microphone mute failed"));
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_addSinkInternal
(JNIEnv * env, jobject caller, jobject jSink)
{
	if (jSink == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "AudioSink must not be null"));
		return 0;
	}

	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	auto sink = new jni::AudioTransportSink(env, jni::JavaGlobalRef<jobject>(env, jSink));

	audioModule->RegisterAudioCallback(sink);

	return reinterpret_cast<jlong>(sink);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_removeSinkInternal
(JNIEnv * env, jobject caller, jlong sinkHandle)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	auto sink = reinterpret_cast<jni::AudioTransportSink *>(sinkHandle);

	if (sink != nullptr) {
		audioModule->RegisterAudioCallback(nullptr);

		delete sink;
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_addSourceInternal
(JNIEnv * env, jobject caller, jobject jSource)
{
	if (jSource == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "AudioSource must not be null"));
		return 0;
	}

	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLEV(audioModule, 0);

	auto source = new jni::AudioTransportSource(env, jni::JavaGlobalRef<jobject>(env, jSource));

	audioModule->RegisterAudioCallback(source);

	return reinterpret_cast<jlong>(source);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_removeSourceInternal
(JNIEnv * env, jobject caller, jlong sourceHandle)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	auto source = reinterpret_cast<jni::AudioTransportSource *>(sourceHandle);

	if (source != nullptr) {
		audioModule->RegisterAudioCallback(nullptr);

		delete source;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_disposeInternal
(JNIEnv * env, jobject caller)
{
	webrtc::AudioDeviceModule * audioModule = GetHandle<webrtc::AudioDeviceModule>(env, caller);
	CHECK_HANDLE(audioModule);

	if (audioModule->Initialized()) {
		audioModule->Terminate();
	}

	rtc::RefCountReleaseStatus status = audioModule->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	audioModule = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDeviceModule_initialize
(JNIEnv * env, jobject caller, jobject jAudioLayer)
{
	std::unique_ptr<webrtc::TaskQueueFactory> taskQueueFactory = webrtc::CreateDefaultTaskQueueFactory();

	if (!taskQueueFactory) {
		env->Throw(jni::JavaError(env, "Create TaskQueueFactory failed"));
		return;
	}

	auto audioLayer = jni::JavaEnums::toNative<webrtc::AudioDeviceModule::AudioLayer>(env, jAudioLayer);

	rtc::scoped_refptr<webrtc::AudioDeviceModule> audioModule = webrtc::AudioDeviceModule::Create(
		audioLayer, taskQueueFactory.release());

	if (!audioModule) {
		env->Throw(jni::JavaError(env, "Create AudioDeviceModule failed"));
		return;
	}

	if (audioModule->Init() != 0) {
		env->Throw(jni::JavaError(env, "Initialize AudioDeviceModule failed"));
		return;
	}

	SetHandle(env, caller, audioModule.release());
}