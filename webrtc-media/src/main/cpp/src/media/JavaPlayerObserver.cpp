/*
 * Copyright 2026 Alex Andres
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

#include "media/JavaPlayerObserver.h"

namespace ffmpeg
{
	JavaPlayerObserver::JavaPlayerObserver(JNIEnv * env, jobject player)
	{
		if (env->GetJavaVM(&vm_) != JNI_OK) {
			return;
		}

		player_ = env->NewGlobalRef(player);

		if (player_ == nullptr) {
			return;
		}

		jclass cls = env->GetObjectClass(player_);

		if (cls == nullptr) {
			return;
		}

		on_state_changed_ = env->GetMethodID(cls, "onNativeStateChanged", "(I)V");
		on_end_of_stream_ = env->GetMethodID(cls, "onNativeEndOfStream", "()V");
		on_error_ = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");

		env->DeleteLocalRef(cls);

		ClearPendingException(env);
	}

	JavaPlayerObserver::~JavaPlayerObserver()
	{
		if (player_ == nullptr || vm_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (env != nullptr) {
			env->DeleteGlobalRef(player_);
		}

		player_ = nullptr;

		Detach(attached);
	}

	void JavaPlayerObserver::OnStateChanged(int state)
	{
		if (on_state_changed_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			env->CallVoidMethod(player_, on_state_changed_, static_cast<jint>(state));

			ClearPendingException(env);
		}

		Detach(attached);
	}

	void JavaPlayerObserver::OnEndOfStream()
	{
		if (on_end_of_stream_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			env->CallVoidMethod(player_, on_end_of_stream_);

			ClearPendingException(env);
		}

		Detach(attached);
	}

	void JavaPlayerObserver::OnError(const std::string & message)
	{
		if (on_error_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			jstring text = env->NewStringUTF(message.c_str());

			if (text != nullptr) {
				env->CallVoidMethod(player_, on_error_, text);

				ClearPendingException(env);

				env->DeleteLocalRef(text);
			}
			else {
				ClearPendingException(env);
			}
		}

		Detach(attached);
	}

	JNIEnv * JavaPlayerObserver::Attach(bool * attached)
	{
		*attached = false;

		if (vm_ == nullptr) {
			return nullptr;
		}

		JNIEnv * env = nullptr;

		jint result = vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);

		if (result == JNI_OK) {
			return env;
		}
		if (result != JNI_EDETACHED) {
			return nullptr;
		}

		// The player's own thread is not one the JVM started, so it has to be
		// introduced before it can call anything.
		if (vm_->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) {
			return nullptr;
		}

		*attached = true;

		return env;
	}

	void JavaPlayerObserver::Detach(bool attached)
	{
		if (attached && vm_ != nullptr) {
			vm_->DetachCurrentThread();
		}
	}

	bool JavaPlayerObserver::CanCallJava(JNIEnv * env)
	{
		// A thread that is already carrying an exception is one returning
		// from native code into Java, which is about to see it thrown. A call
		// into Java now is not allowed, and clearing afterwards would swallow
		// an exception that was never ours, so the event is dropped instead.
		return env != nullptr && env->ExceptionCheck() == JNI_FALSE;
	}

	void JavaPlayerObserver::ClearPendingException(JNIEnv * env)
	{
		if (env->ExceptionCheck() == JNI_TRUE) {
			// There is nowhere to throw this: the thread below is decoding,
			// not running Java. Reporting and clearing keeps it from taking
			// down the next unrelated JNI call instead.
			env->ExceptionDescribe();
			env->ExceptionClear();
		}
	}
}
