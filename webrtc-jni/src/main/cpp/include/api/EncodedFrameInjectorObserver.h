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

#ifndef JNI_WEBRTC_API_ENCODED_FRAME_INJECTOR_OBSERVER_H_
#define JNI_WEBRTC_API_ENCODED_FRAME_INJECTOR_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/encoded_audio_frame_injector_interface.h"
#include "api/encoded_video_frame_injector_interface.h"

#include <jni.h>
#include <memory>
#include <mutex>

namespace jni
{
	// Forwards the requests of an encoded frame injector to its Java observer.
	//
	// WebRTC makes them on its encoder threads; the Java observer is called on
	// the JavaCallbackThread instead, so that it may call back into WebRTC.
	// WebRTC keeps the callbacks for as long as the injector lives, which can
	// be longer than this object, so they only share its state.
	class EncodedFrameInjectorObserver
	{
		public:
			// For an RTCEncodedVideoFrameInjectorObserver.
			static std::unique_ptr<EncodedFrameInjectorObserver> ForVideo(JNIEnv * env, const JavaRef<jobject> & observer);

			// For an RTCEncodedAudioFrameInjectorObserver.
			static std::unique_ptr<EncodedFrameInjectorObserver> ForAudio(JNIEnv * env, const JavaRef<jobject> & observer);

			// Waits for a Java call in progress, so the Java observer hears
			// nothing more once this returns.
			~EncodedFrameInjectorObserver();

			EncodedFrameInjectorObserver(const EncodedFrameInjectorObserver &) = delete;
			EncodedFrameInjectorObserver & operator=(const EncodedFrameInjectorObserver &) = delete;

			webrtc::KeyFrameCallback CreateKeyFrameCallback() const;
			webrtc::BitrateInfoCallback CreateBitrateInfoCallback() const;
			webrtc::TargetBitrateCallback CreateTargetBitrateCallback() const;

		private:
			class JavaVideoObserverClass : public JavaClass
			{
				public:
					explicit JavaVideoObserverClass(JNIEnv * env);

					jmethodID onKeyFrameRequest;
					jmethodID onBitrateChange;
			};

			class JavaAudioObserverClass : public JavaClass
			{
				public:
					explicit JavaAudioObserverClass(JNIEnv * env);

					jmethodID onTargetBitrateChange;
			};

			struct Target
			{
				Target(JNIEnv * env, const JavaRef<jobject> & observer);

				// Recursive, so that a Java observer may dispose its injector.
				std::recursive_mutex mutex;
				bool active;
				const JavaGlobalRef<jobject> observer;
				jmethodID onKeyFrameRequest;
				jmethodID onBitrateChange;
				jmethodID onTargetBitrateChange;
			};

			explicit EncodedFrameInjectorObserver(std::shared_ptr<Target> target);

			// Calls the Java observer on the JavaCallbackThread, unless it is
			// no longer active by then.
			template <typename Call>
			static void Post(const std::shared_ptr<Target> & target, Call call);

		private:
			const std::shared_ptr<Target> target;
	};
}

#endif
