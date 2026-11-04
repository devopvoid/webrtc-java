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

#ifndef JNI_WEBRTC_NATIVE_HANDLE_H_
#define JNI_WEBRTC_NATIVE_HANDLE_H_

#include <jni.h>

#include <functional>

namespace jni
{
	// The life of the native object behind NativeObject.nativeHandle, shared
	// by every thread that calls into it.
	//
	// A native method uses the object through a NativeLease, which marks the
	// handle in use for as long as the call runs. dispose() and close() take
	// the handle with Take(), which unbinds it in one step: of several threads
	// disposing the same object, one gets the handle, the others nothing, and
	// no call starts using it afterwards. The object's owning reference is then
	// given up with Release(): at once if no call uses the handle, otherwise by
	// the last of those calls when it returns. Nothing waits for another
	// thread, so a dispose from within a callback cannot deadlock with a call
	// that waits for that callback's thread.
	namespace NativeHandle
	{
		// Returns the bound handle of a NativeObject and marks it in use, or 0
		// when the object is null or no longer bound. A non-zero handle must be
		// given back with Unuse().
		jlong Use(JNIEnv * env, jobject object);

		// Ends a use begun with Use() or Pin(), and runs the releases that
		// waited for it if it was the last.
		void Unuse(JNIEnv * env, jlong handle) noexcept;

		// Marks a handle in use once more, for work that outlives the call
		// using it, which is to end that use with Unuse() when it is done. Only
		// for a handle the calling thread uses, e.g. through a NativeLease.
		void Pin(jlong handle);

		// Unbinds the handle of a NativeObject and returns it. Returns 0 when
		// the object is null or another thread has taken the handle before.
		jlong Take(JNIEnv * env, jobject object);

		// Gives up what a taken handle owns. The release runs right away, with
		// deferred false, unless a call still uses the handle; then it runs on
		// the thread of the last such call, when that call returns, with
		// deferred true. A deferred release must not use the JNIEnv or the Java
		// object of the call that took the handle.
		void Release(JNIEnv * env, jlong handle, std::function<void(bool deferred)> release);

		// Throws the IllegalStateException of a disposed object, unless an
		// exception is pending already.
		void ThrowDisposed(JNIEnv * env, jobject object) noexcept;

		// Reports that a release left references to the native object behind:
		// as a java.lang.Error when it ran right away, as a warning in the log
		// when it was deferred, since the call it ran in is not the one to fail.
		void ReportNotDeleted(bool deferred) noexcept;
	}

	// The native object of a NativeObject, kept from being released while the
	// lease lives. Converts to T *, which is null when the object has been
	// disposed: check it with CHECK_HANDLE, which then throws the object's
	// IllegalStateException.
	template <typename T>
	class NativeLease
	{
		public:
			NativeLease(JNIEnv * env, jobject object) :
				env(env),
				object(object),
				handle(NativeHandle::Use(env, object))
			{
			}

			~NativeLease()
			{
				if (handle != 0) {
					NativeHandle::Unuse(env, handle);
				}
			}

			NativeLease(const NativeLease &) = delete;
			NativeLease & operator=(const NativeLease &) = delete;

			T * get() const
			{
				return reinterpret_cast<T *>(handle);
			}

			operator T * () const
			{
				return get();
			}

			T * operator->() const
			{
				return get();
			}

			void throwDisposed() const
			{
				NativeHandle::ThrowDisposed(env, object);
			}

		private:
			JNIEnv * env;
			jobject object;
			jlong handle;
	};

	// Found by argument-dependent lookup from CHECK_HANDLE.
	template <typename T>
	void ThrowNullHandle(const NativeLease<T> & lease)
	{
		lease.throwDisposed();
	}

	// NativeHandle::Take() for the native type the handle stands for. Null
	// when the object has been disposed already.
	template <typename T>
	T * TakeHandle(JNIEnv * env, jobject object)
	{
		return reinterpret_cast<T *>(NativeHandle::Take(env, object));
	}

	// NativeHandle::Release() for a handle taken with TakeHandle().
	template <typename T>
	void ReleaseHandle(JNIEnv * env, T * object, std::function<void(bool deferred)> release)
	{
		NativeHandle::Release(env, reinterpret_cast<jlong>(object), std::move(release));
	}
}

#endif
