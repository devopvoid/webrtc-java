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
#include "media/JniBoundary.h"

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
		on_segment_finished_ = env->GetMethodID(cls, "onNativeSegmentFinished", "(JLjava/lang/String;JJI)V");
		on_replay_saved_ = env->GetMethodID(cls, "onNativeReplaySaved", "(IIJJLjava/lang/String;)V");

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

	void JavaRecorderObserver::OnSegmentFinished(int64_t index, const std::string & path,
			int64_t start_us, int64_t duration_us, int result)
	{
		if (on_segment_finished_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			jstring jPath = NewJavaString(env, path);

			if (jPath != nullptr) {
				env->CallVoidMethod(recorder_, on_segment_finished_, static_cast<jlong>(index), jPath,
						static_cast<jlong>(start_us), static_cast<jlong>(duration_us), static_cast<jint>(result));
				env->DeleteLocalRef(jPath);
			}

			ClearPendingException(env);
		}

		Detach(attached);
	}

	void JavaRecorderObserver::OnReplaySaved(int id, int result, int64_t start_us,
			int64_t duration_us, const std::string & error)
	{
		if (on_replay_saved_ == nullptr) {
			return;
		}

		bool attached = false;
		JNIEnv * env = Attach(&attached);

		if (CanCallJava(env)) {
			// No text for no error.
			jstring jError = error.empty() ? nullptr : NewJavaString(env, error);

			if (error.empty() || jError != nullptr) {
				env->CallVoidMethod(recorder_, on_replay_saved_, static_cast<jint>(id), static_cast<jint>(result),
						static_cast<jlong>(start_us), static_cast<jlong>(duration_us), jError);
			}
			if (jError != nullptr) {
				env->DeleteLocalRef(jError);
			}

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
			jstring text = NewJavaString(env, message);

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
		ReportPendingException(env);
	}
}
