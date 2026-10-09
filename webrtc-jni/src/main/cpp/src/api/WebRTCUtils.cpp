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

#include "api/WebRTCUtils.h"
#include "JavaRef.h"

namespace jni
{
	std::string RTCErrorToString(const webrtc::RTCError & error)
	{
		std::string type(ToString(error.type()));
		std::string message = error.message();

		return "[" + type + "] " + message;
	}

	// ExceptionDescribe would print the "Exception in thread" prefix straight to
	// native stderr and the stack trace to System.err, splitting the report
	// across two streams and leaving the prefix on an unterminated line.
	void ReportUncaughtException(JNIEnv * env, jthrowable exception)
	{
		JavaLocalRef<jclass> threadClass(env, env->FindClass("java/lang/Thread"));
		JavaLocalRef<jclass> handlerClass(env, env->FindClass("java/lang/Thread$UncaughtExceptionHandler"));
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
			return;
		}

		jmethodID currentThread = env->GetStaticMethodID(threadClass.get(), "currentThread", "()Ljava/lang/Thread;");
		jmethodID getHandler = env->GetMethodID(threadClass.get(), "getUncaughtExceptionHandler",
			"()Ljava/lang/Thread$UncaughtExceptionHandler;");
		jmethodID uncaughtException = env->GetMethodID(handlerClass.get(), "uncaughtException",
			"(Ljava/lang/Thread;Ljava/lang/Throwable;)V");
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
			return;
		}

		JavaLocalRef<jobject> thread(env, env->CallStaticObjectMethod(threadClass.get(), currentThread));
		JavaLocalRef<jobject> handler(env, env->CallObjectMethod(thread.get(), getHandler));
		if (!env->ExceptionCheck() && handler.get()) {
			env->CallVoidMethod(handler.get(), uncaughtException, thread.get(), exception);
		}
		// Like the JVM, ignore an exception thrown by the handler itself.
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
		}
	}
}
