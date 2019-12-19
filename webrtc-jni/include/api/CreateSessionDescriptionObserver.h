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

#ifndef JNI_WEBRTC_API_CREATE_SESSION_DESCRIPTION_OBSERVER_H_
#define JNI_WEBRTC_API_CREATE_SESSION_DESCRIPTION_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/jsep.h"

#include <jni.h>

namespace jni
{
	class CreateSessionDescriptionObserver : public webrtc::CreateSessionDescriptionObserver
	{
		public:
			explicit CreateSessionDescriptionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer);
			~CreateSessionDescriptionObserver() = default;

			// SetSessionDescriptionObserver implementation.
			void OnSuccess(webrtc::SessionDescriptionInterface * desc) override;
			void OnFailure(webrtc::RTCError error) override;

		private:
			class JavaCreateSessionDescObserverClass : public JavaClass
			{
				public:
					explicit JavaCreateSessionDescObserverClass(JNIEnv * env);

					jmethodID onSuccess;
					jmethodID onFailure;
			};

		private:
			JavaGlobalRef<jobject> observer;

			const std::shared_ptr<JavaCreateSessionDescObserverClass> javaClass;
	};
}

#endif