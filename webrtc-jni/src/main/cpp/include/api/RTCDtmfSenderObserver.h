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

#ifndef JNI_WEBRTC_API_RTC_DTMF_SENDER_OBSERVER_H_
#define JNI_WEBRTC_API_RTC_DTMF_SENDER_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/dtmf_sender_interface.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class RTCDtmfSenderObserver : public webrtc::DtmfSenderObserverInterface
	{
		public:
			explicit RTCDtmfSenderObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer);
			~RTCDtmfSenderObserver() = default;

			// DtmfSenderObserverInterface implementation.
			void OnToneChange(const std::string & tone, const std::string & tone_buffer) override;

		private:
			class JavaRTCDtmfSenderObserverClass : public JavaClass
			{
				public:
					explicit JavaRTCDtmfSenderObserverClass(JNIEnv * env);

					jmethodID onToneChange;
			};

		private:
			JavaGlobalRef<jobject> observer;

			const std::shared_ptr<JavaRTCDtmfSenderObserverClass> javaClass;
	};
}

#endif