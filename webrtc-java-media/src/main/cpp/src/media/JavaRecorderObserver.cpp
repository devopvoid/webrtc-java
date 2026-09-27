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

#include "media/JavaRecorderObserver.h"

namespace ffmpeg
{
	JavaRecorderObserver::JavaRecorderObserver(JNIEnv * env, jobject recorder)
	{
		if (env->GetJavaVM(&vm_) != JNI_OK) {
			return;
		}

		recorder_ = env->NewGlobalRef(recorder);

		if (recorder_ == nullptr) {
			return;
		}

		jclass cls = env->GetObjectClass(recorder_);

		if (cls == nullptr) {
			return;
		}

		on_started_ = env->GetMethodID(cls, "onNativeStarted", "()V");
		on_warning_ = env->GetMethodID(cls, "onNativeWarning", "(Ljava/lang/String;)V");
		on_error_ = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
		on_key_frame_needed_ = env->GetMethodID(cls, "onNativeKeyFrameNeeded", "(I)V");

		env->DeleteLocalRef(cls);

		ClearPendingException(env);
	}

	JavaRecorderObserver::~JavaRecorderObserver()
	{
		if (recorder_ == nullptr || vm_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (env != nullptr) {
			env->DeleteGlobalRef(recorder_);
		}

		recorder_ = nullptr;

		Detach(attached);
	}

	void JavaRecorderObserver::OnStarted()
	{
		if (on_started_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			env->CallVoidMethod(recorder_, on_started_);

			ClearPendingException(env);
		}

		Detach(attached);
	}

	void JavaRecorderObserver::OnWarning(const std::string & message)
	{
		CallWithMessage(on_warning_, message);
	}

	void JavaRecorderObserver::OnError(const std::string & message)
	{
		CallWithMessage(on_error_, message);
	}

	void JavaRecorderObserver::OnKeyFrameNeeded(int track)
	{
		if (on_key_frame_needed_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			env->CallVoidMethod(recorder_, on_key_frame_needed_, static_cast<jint>(track));

			ClearPendingException(env);
		}

		Detach(attached);
	}

	void JavaRecorderObserver::CallWithMessage(jmethodID method, const std::string & message)
	{
		if (method == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			jstring text = env->NewStringUTF(message.c_str());

			if (text != nullptr) {
				env->CallVoidMethod(recorder_, method, text);
				env->DeleteLocalRef(text);
			}

			ClearPendingException(env);
		}

		Detach(attached);
	}

	JNIEnv * JavaRecorderObserver::Attach(bool * attached)
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

		if (vm_->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) {
			return nullptr;
		}

		*attached = true;

		return env;
	}

	void JavaRecorderObserver::Detach(bool attached)
	{
		if (attached && vm_ != nullptr) {
			vm_->DetachCurrentThread();
		}
	}

	bool JavaRecorderObserver::CanCallJava(JNIEnv * env)
	{
		return env != nullptr && env->ExceptionCheck() == JNI_FALSE;
	}

	void JavaRecorderObserver::ClearPendingException(JNIEnv * env)
	{
		if (env->ExceptionCheck() == JNI_TRUE) {
			env->ExceptionDescribe();
			env->ExceptionClear();
		}
	}
}
