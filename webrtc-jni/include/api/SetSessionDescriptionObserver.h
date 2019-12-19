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

#ifndef JNI_WEBRTC_API_SET_SESSION_DESCRIPTION_OBSERVER_H_
#define JNI_WEBRTC_API_SET_SESSION_DESCRIPTION_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/jsep.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class SetSessionDescriptionObserver : public webrtc::SetSessionDescriptionObserver
	{
		public:
			explicit SetSessionDescriptionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer);
			~SetSessionDescriptionObserver() = default;

			// SetSessionDescriptionObserver implementation.
			void OnSuccess() override;
			void OnFailure(webrtc::RTCError error) override;

		private:
			class JavaSetSessionDescObserverClass : public JavaClass
			{
				public:
					explicit JavaSetSessionDescObserverClass(JNIEnv * env);

					jmethodID onSuccess;
					jmethodID onFailure;
			};

		private:
			JavaGlobalRef<jobject> observer;

			const std::shared_ptr<JavaSetSessionDescObserverClass> javaClass;
	};
}

#endif