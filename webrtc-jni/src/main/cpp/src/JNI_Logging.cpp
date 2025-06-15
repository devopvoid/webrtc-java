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

#include "JNI_Logging.h"
#include "rtc/LogSink.h"
#include "JavaEnums.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "rtc_base/logging.h"

#include <memory>

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_logging_Logging_addLogSink
(JNIEnv * env, jclass caller, jobject jseverity, jobject jsink)
{
	try {
		auto severity = jni::JavaEnums::toNative<rtc::LoggingSeverity>(env, jseverity);

		rtc::LogMessage::AddLogToStream(new jni::LogSink(env, jni::JavaGlobalRef<jobject>(env, jsink)), severity);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_logging_Logging_log
(JNIEnv * env, jclass caller, jobject jseverity, jstring jmessage)
{
	std::string message = jni::JavaString::toNative(env, jni::JavaLocalRef<jstring>(env, jmessage));
	
	auto severity = jni::JavaEnums::toNative<rtc::LoggingSeverity>(env, jseverity);

	RTC_LOG_V(severity) << message;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_logging_Logging_logToDebug
(JNIEnv * env, jclass caller, jobject jseverity)
{
	int rtcSeverity = jni::JavaEnums::toNative<rtc::LoggingSeverity>(env, jseverity);

	if (rtcSeverity >= rtc::LS_VERBOSE && rtcSeverity <= rtc::LS_NONE) {
		rtc::LogMessage::LogToDebug(static_cast<rtc::LoggingSeverity>(rtcSeverity));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_logging_Logging_logThreads
(JNIEnv * env, jclass caller, jboolean enable)
{
	rtc::LogMessage::LogThreads(static_cast<bool>(enable));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_logging_Logging_logTimestamps
(JNIEnv * env, jclass caller, jboolean enable)
{
	rtc::LogMessage::LogTimestamps(static_cast<bool>(enable));
}