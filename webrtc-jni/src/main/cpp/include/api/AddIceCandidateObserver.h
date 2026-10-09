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

#ifndef JNI_WEBRTC_API_ADD_ICE_CANDIDATE_OBSERVER_H_
#define JNI_WEBRTC_API_ADD_ICE_CANDIDATE_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/rtc_error.h"

#include <jni.h>
#include <memory>

namespace jni
{
	// Reports the outcome of PeerConnectionInterface::AddIceCandidate to a
	// Java AddIceCandidateObserver, exactly once.
	class AddIceCandidateObserver
	{
		public:
			AddIceCandidateObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer);
			~AddIceCandidateObserver();

			AddIceCandidateObserver(const AddIceCandidateObserver &) = delete;
			AddIceCandidateObserver & operator=(const AddIceCandidateObserver &) = delete;

			void OnComplete(webrtc::RTCError error) noexcept;

		private:
			class JavaAddIceCandidateObserverClass : public JavaClass
			{
				public:
					explicit JavaAddIceCandidateObserverClass(JNIEnv * env);

					jmethodID onSuccess;
					jmethodID onFailure;
			};

			void Notify(const char * error) noexcept;

			JavaGlobalRef<jobject> observer;

			const std::shared_ptr<JavaAddIceCandidateObserverClass> javaClass;
	};
}

#endif
