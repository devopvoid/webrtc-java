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

#include "api/EncodedFrameInjectorObserver.h"
#include "api/JavaCallbackThread.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <utility>

namespace jni
{
	std::unique_ptr<EncodedFrameInjectorObserver> EncodedFrameInjectorObserver::ForVideo(JNIEnv * env, const JavaRef<jobject> & observer)
	{
		auto target = std::make_shared<Target>(env, observer);
		const auto javaClass = JavaClasses::get<JavaVideoObserverClass>(env);

		target->onKeyFrameRequest = javaClass->onKeyFrameRequest;
		target->onBitrateChange = javaClass->onBitrateChange;

		return std::unique_ptr<EncodedFrameInjectorObserver>(new EncodedFrameInjectorObserver(std::move(target)));
	}

	std::unique_ptr<EncodedFrameInjectorObserver> EncodedFrameInjectorObserver::ForAudio(JNIEnv * env, const JavaRef<jobject> & observer)
	{
		auto target = std::make_shared<Target>(env, observer);
		const auto javaClass = JavaClasses::get<JavaAudioObserverClass>(env);

		target->onTargetBitrateChange = javaClass->onTargetBitrateChange;

		return std::unique_ptr<EncodedFrameInjectorObserver>(new EncodedFrameInjectorObserver(std::move(target)));
	}

	EncodedFrameInjectorObserver::EncodedFrameInjectorObserver(std::shared_ptr<Target> target) :
		target(std::move(target))
	{
	}

	EncodedFrameInjectorObserver::~EncodedFrameInjectorObserver()
	{
		std::lock_guard<std::recursive_mutex> lock(target->mutex);

		target->active = false;
	}

	template <typename Call>
	void EncodedFrameInjectorObserver::Post(const std::shared_ptr<Target> & target, Call call)
	{
		JavaCallbackThread::Post([target, call = std::move(call)](JNIEnv * env) {
			std::lock_guard<std::recursive_mutex> lock(target->mutex);

			if (!target->active) {
				return;
			}

			call(env, *target);

			// A Java exception must not escape into the next callback.
			ReportPendingException(env);
		});
	}

	webrtc::KeyFrameCallback EncodedFrameInjectorObserver::CreateKeyFrameCallback() const
	{
		return [target = target]() {
			Post(target, [](JNIEnv * env, Target & target) {
				env->CallVoidMethod(target.observer, target.onKeyFrameRequest);
			});
		};
	}

	webrtc::BitrateInfoCallback EncodedFrameInjectorObserver::CreateBitrateInfoCallback() const
	{
		return [target = target](int32_t allocatedBitrate, int32_t availableOutgoingBitrate) {
			Post(target, [allocatedBitrate, availableOutgoingBitrate](JNIEnv * env, Target & target) {
				env->CallVoidMethod(target.observer, target.onBitrateChange,
					static_cast<jint>(allocatedBitrate), static_cast<jint>(availableOutgoingBitrate));
			});
		};
	}

	webrtc::TargetBitrateCallback EncodedFrameInjectorObserver::CreateTargetBitrateCallback() const
	{
		return [target = target](int32_t targetBitrate) {
			Post(target, [targetBitrate](JNIEnv * env, Target & target) {
				env->CallVoidMethod(target.observer, target.onTargetBitrateChange, static_cast<jint>(targetBitrate));
			});
		};
	}

	EncodedFrameInjectorObserver::Target::Target(JNIEnv * env, const JavaRef<jobject> & observer) :
		active(true),
		observer(env, observer.get()),
		onKeyFrameRequest(nullptr),
		onBitrateChange(nullptr),
		onTargetBitrateChange(nullptr)
	{
	}

	EncodedFrameInjectorObserver::JavaVideoObserverClass::JavaVideoObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCEncodedVideoFrameInjectorObserver");

		onKeyFrameRequest = GetMethod(env, cls, "onKeyFrameRequest", "()V");
		onBitrateChange = GetMethod(env, cls, "onBitrateChange", "(II)V");
	}

	EncodedFrameInjectorObserver::JavaAudioObserverClass::JavaAudioObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCEncodedAudioFrameInjectorObserver");

		onTargetBitrateChange = GetMethod(env, cls, "onTargetBitrateChange", "(I)V");
	}
}
