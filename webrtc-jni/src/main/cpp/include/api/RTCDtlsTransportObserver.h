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

#ifndef JNI_WEBRTC_API_RTC_DTLS_TRANSPORT_OBSERVER_H_
#define JNI_WEBRTC_API_RTC_DTLS_TRANSPORT_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/dtls_transport_interface.h"

#include <jni.h>
#include <memory>
#include <mutex>

namespace jni
{
	// WebRTC notifies this observer on its network thread; the Java observer is
	// called on the JavaCallbackThread instead.
	class RTCDtlsTransportObserver : public webrtc::DtlsTransportObserverInterface
	{
		public:
			explicit RTCDtlsTransportObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer);

			// Waits for a Java call in progress, so the Java observer hears
			// nothing more once this returns.
			~RTCDtlsTransportObserver();

			// DtlsTransportObserverInterface implementation.
			void OnStateChange(webrtc::DtlsTransportInformation info) override;
			void OnError(webrtc::RTCError error) override;

		private:
			class JavaRTCDtlsTransportObserverClass : public JavaClass
			{
				public:
					explicit JavaRTCDtlsTransportObserverClass(JNIEnv * env);

					jmethodID onStateChange;
					jmethodID onError;
			};

			// Shared with the queued calls, which may outlive this observer.
			struct Target
			{
				Target(const JavaGlobalRef<jobject> & observer,
					std::shared_ptr<JavaRTCDtlsTransportObserverClass> javaClass);

				// Recursive, so that a Java observer may unregister itself.
				std::recursive_mutex mutex;
				bool active;
				const JavaGlobalRef<jobject> observer;
				const std::shared_ptr<JavaRTCDtlsTransportObserverClass> javaClass;
			};

		private:
			const std::shared_ptr<Target> target;
	};
}

#endif