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

	void LogSink::OnLogMessage(const std::string & message, webrtc::LoggingSeverity severity)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		// WebRTC holds its global logging lock while it calls a sink, and it
		// is built without unwinding, so nothing may throw from here: an
		// exception passing through WebRTC's frames would leave that lock
		// held and every thread that logs next would block forever.
		//
		// The code that logged may have a Java exception pending, which no
		// JNI call may be made with. It is set aside and restored after.
		jthrowable pending = env->ExceptionOccurred();

		if (pending != nullptr) {
			env->ExceptionClear();
		}

		try {
			JavaLocalRef<jobject> jSeverity = JavaEnums::toJava(env, severity);
			JavaLocalRef<jstring> jMessage = JavaString::toJava(env, message);

			env->CallVoidMethod(javaSink, javaClass->onLogMessage, jSeverity.get(), jMessage.get());

			// What the sink threw is reported, but not rethrown.
			if (env->ExceptionCheck()) {
				env->ExceptionDescribe();
				env->ExceptionClear();
			}
		}
		catch (...) {
			env->ExceptionClear();
		}

		if (pending != nullptr) {
			env->Throw(pending);
			env->DeleteLocalRef(pending);
		}
	}

	LogSink::JavaLogSinkClass::JavaLogSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_LOG"LogSink");

		onLogMessage = GetMethod(env, cls, "onLogMessage", "(L" PKG_LOG "Logging$Severity;" STRING_SIG ")V");
	}
}
