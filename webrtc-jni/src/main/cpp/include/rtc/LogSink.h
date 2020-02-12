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

#ifndef JNI_WEBRTC_RTC_LOG_SINK_H_
#define JNI_WEBRTC_RTC_LOG_SINK_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "rtc_base/logging.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class LogSink : public rtc::LogSink
	{
		public:
			explicit LogSink(JNIEnv * env, const JavaGlobalRef<jobject> & javaSink);
			~LogSink() = default;

			// LogSink implementation.
			void OnLogMessage(const std::string & message) override;
			void OnLogMessage(const std::string & message, rtc::LoggingSeverity severity) override;

		private:
			class JavaLogSinkClass : public JavaClass
			{
				public:
					explicit JavaLogSinkClass(JNIEnv * env);

					jmethodID onLogMessage;
			};

		private:
			JavaGlobalRef<jobject> javaSink;

			const std::shared_ptr<JavaLogSinkClass> javaClass;
	};
}

#endif