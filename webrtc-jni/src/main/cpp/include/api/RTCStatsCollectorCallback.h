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

#ifndef JNI_WEBRTC_API_RTC_STATS_COLLECTOR_CALLBACK_H_
#define JNI_WEBRTC_API_RTC_STATS_COLLECTOR_CALLBACK_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/stats/rtc_stats_collector_callback.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class RTCStatsCollectorCallback : public webrtc::RTCStatsCollectorCallback
	{
		public:
			RTCStatsCollectorCallback(JNIEnv * env, const JavaGlobalRef<jobject> & callback);
			~RTCStatsCollectorCallback() = default;

			void OnStatsDelivered(const rtc::scoped_refptr<const webrtc::RTCStatsReport> & report) override;

		private:
			class JavaRTCStatsCollectorCallbackClass : public JavaClass
			{
				public:
					explicit JavaRTCStatsCollectorCallbackClass(JNIEnv * env);

					jmethodID onStatsDelivered;
			};

		private:
			JavaGlobalRef<jobject> callback;

			const std::shared_ptr<JavaRTCStatsCollectorCallbackClass> javaClass;
	};
}

#endif