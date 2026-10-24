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

#ifndef WEBRTC_JAVA_MEDIA_JNI_BOUNDARY_H_
#define WEBRTC_JAVA_MEDIA_JNI_BOUNDARY_H_

#include <jni.h>

#include <string>

namespace ffmpeg
{
	// Throws a Java exception of the given class, unless one is pending
	// already, which then stays the one Java sees. Allocates nothing on the
	// C++ heap, so that it can also report that an allocation failed.
	void ThrowJavaException(JNIEnv * env, const char * class_name, const char * message) noexcept;

	void ThrowIOException(JNIEnv * env, const std::string & message) noexcept;

	// Turns the C++ exception being handled into a Java one: a failed
	// allocation into an OutOfMemoryError, anything else into a
	// RuntimeException. Only to be called from a catch block.
	void ThrowCurrentException(JNIEnv * env) noexcept;

	// Runs the body of a JNI entry point, which must not let a C++ exception
	// reach the VM: one that does ends the process. A C++ exception becomes a
	// Java one, and the entry point returns `on_error`. Whatever the body
	// holds is released before the Java exception is thrown, so a destructor
	// may still call into Java.
	template <typename R, typename Body>
	R CallFromJava(JNIEnv * env, R on_error, Body && body) noexcept
	{
		try {
			return body();
		}
		catch (...) {
			ThrowCurrentException(env);
		}

		return on_error;
	}

	template <typename Body>
	void CallFromJava(JNIEnv * env, Body && body) noexcept
	{
		try {
			body();
		}
		catch (...) {
			ThrowCurrentException(env);
		}
	}

	// A local reference, deleted when this goes out of scope.
	template <typename T>
	class LocalRef
	{
		public:
			LocalRef(JNIEnv * env, T ref) noexcept : env_(env), ref_(ref)
			{
			}

			~LocalRef()
			{
				if (ref_ != nullptr) {
					env_->DeleteLocalRef(ref_);
				}
			}

			LocalRef(const LocalRef &) = delete;
			LocalRef & operator=(const LocalRef &) = delete;

			T get() const noexcept
			{
				return ref_;
			}

			// Gives the reference to the caller, such as the VM it is returned to.
			T release() noexcept
			{
				T ref = ref_;
				ref_ = nullptr;

				return ref;
			}

		private:
			JNIEnv * env_;
			T ref_;
	};

	// The modified UTF-8 of a Java string, released when this goes out of
	// scope, also when a copy of it fails on the way out.
	class JavaStringChars
	{
		public:
			// get() is null when the string is null or the VM could not
			// provide the characters, and a Java exception is pending then.
			JavaStringChars(JNIEnv * env, jstring string) noexcept;
			~JavaStringChars();

			JavaStringChars(const JavaStringChars &) = delete;
			JavaStringChars & operator=(const JavaStringChars &) = delete;

			const char * get() const noexcept
			{
				return chars_;
			}

			// A copy that outlives this object. Throws std::bad_alloc.
			std::string str() const;

			// How many strings are held right now, for the tests to see that
			// none is left behind.
			static int Held() noexcept;

		private:
			JNIEnv * env_;
			jstring string_;
			const char * chars_;
	};
}

#endif
