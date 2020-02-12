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

#include "rtc/LogSink.h"
#include "JavaEnums.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	LogSink::LogSink(JNIEnv * env, const JavaGlobalRef<jobject> & javaSink) :
		javaSink(javaSink),
		javaClass(JavaClasses::get<JavaLogSinkClass>(env))
	{
	}

	void LogSink::OnLogMessage(const std::string & message)
	{
	}

	void LogSink::OnLogMessage(const std::string & message, rtc::LoggingSeverity severity)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jobject> jSeverity = JavaEnums::toJava(env, severity);
		JavaLocalRef<jstring> jMessage = JavaString::toJava(env, message);

		env->CallVoidMethod(javaSink, javaClass->onLogMessage, jSeverity.get(), jMessage.get());
	}

	LogSink::JavaLogSinkClass::JavaLogSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_LOG"LogSink");

		onLogMessage = GetMethod(env, cls, "onLogMessage", "(L" PKG_LOG "Logging$Severity;" STRING_SIG ")V");
	}
}