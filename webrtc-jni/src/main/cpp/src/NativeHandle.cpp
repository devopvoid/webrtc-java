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

#include "NativeHandle.h"
#include "AllocationFault.h"
#include "JavaClassLoader.h"
#include "JavaRef.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"
#include "LeakDetection.h"

#include "rtc_base/logging.h"

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

namespace jni
{
	namespace
	{
		struct Entry
		{
			int uses = 0;
			std::vector<std::function<void(bool)>> releases;
		};

		// Uses and takes of a handle meet in the stripe of that handle, so
		// that calls on different objects rarely wait for each other.
		struct Stripe
		{
			std::mutex mutex;
			std::unordered_map<jlong, Entry> entries;
		};

		constexpr size_t kStripes = 64;

		Stripe & StripeOf(jlong handle)
		{
			static Stripe stripes[kStripes];

			uint64_t value = static_cast<uint64_t>(handle);

			// Heap addresses share their low bits.
			value ^= value >> 33;
			value *= 0xff51afd7ed558ccdULL;
			value ^= value >> 33;

			return stripes[value % kStripes];
		}

		struct NativeObjectClass
		{
			jclass cls = nullptr;
			jfieldID nativeHandle = nullptr;
			jmethodID disposedException = nullptr;
		};

		// Resolved once; null members when that failed, with the exception
		// pending in the thread that tried.
		const NativeObjectClass & GetNativeObjectClass(JNIEnv * env)
		{
			static std::once_flag once;
			static NativeObjectClass instance;

			std::call_once(once, [env]() {
				JavaLocalRef<jclass> cls = ClassLoaderGetClass(env, PKG_INTERNAL "NativeObject");

				if (cls.get() == nullptr) {
					return;
				}

				jfieldID field = env->GetFieldID(cls, "nativeHandle", "J");

				if (field == nullptr) {
					return;
				}

				jmethodID method = env->GetMethodID(cls, "disposedException",
					"()Ljava/lang/IllegalStateException;");

				if (method == nullptr) {
					return;
				}

				instance.cls = static_cast<jclass>(env->NewGlobalRef(cls));
				instance.nativeHandle = field;
				instance.disposedException = method;
			});

			return instance;
		}

		jfieldID HandleField(JNIEnv * env)
		{
			return GetNativeObjectClass(env).nativeHandle;
		}
	}

	namespace NativeHandle
	{
		jlong Use(JNIEnv * env, jobject object)
		{
			if (object == nullptr) {
				return 0;
			}

			jfieldID field = HandleField(env);

			if (field == nullptr) {
				return 0;
			}

			jlong handle = env->GetLongField(object, field);

			if (handle == 0) {
				return 0;
			}

			Stripe & stripe = StripeOf(handle);
			std::lock_guard<std::mutex> lock(stripe.mutex);

			// Read again where Take() writes: it may have unbound the handle
			// between the first read and the lock.
			if (env->GetLongField(object, field) != handle) {
				return 0;
			}

			AllocationFault::CheckNativeLease();
			stripe.entries[handle].uses++;

			return handle;
		}

		void Unuse(JNIEnv * env, jlong handle) noexcept
		{
			std::vector<std::function<void(bool)>> releases;

			{
				Stripe & stripe = StripeOf(handle);
				std::lock_guard<std::mutex> lock(stripe.mutex);

				auto found = stripe.entries.find(handle);

				if (found == stripe.entries.end()) {
					return;
				}

				if (--found->second.uses > 0) {
					return;
				}

				releases = std::move(found->second.releases);

				stripe.entries.erase(found);
			}

			if (releases.empty()) {
				return;
			}

			// The call may be returning with an exception; a release may need
			// JNI, which is not to be used with one pending.
			jthrowable pending = env->ExceptionOccurred();

			if (pending != nullptr) {
				env->ExceptionClear();
			}

			for (auto & release : releases) {
				try {
					release(true);
				}
				catch (...) {
					RTC_LOG(LS_ERROR) << "Release of a disposed native object failed";
				}

				if (env->ExceptionCheck()) {
					env->ExceptionClear();
				}
			}

			if (pending != nullptr) {
				env->Throw(pending);
				env->DeleteLocalRef(pending);
			}
		}

		void Pin(jlong handle)
		{
			Stripe & stripe = StripeOf(handle);
			std::lock_guard<std::mutex> lock(stripe.mutex);

			// The caller's own use keeps the entry.
			AllocationFault::CheckNativeLease();
			stripe.entries[handle].uses++;
		}

		jlong Take(JNIEnv * env, jobject object)
		{
			if (object == nullptr) {
				return 0;
			}

			jfieldID field = HandleField(env);

			if (field == nullptr) {
				return 0;
			}

			jlong handle = env->GetLongField(object, field);

			if (handle == 0) {
				return 0;
			}

			{
				Stripe & stripe = StripeOf(handle);
				std::lock_guard<std::mutex> lock(stripe.mutex);

				if (env->GetLongField(object, field) != handle) {
					return 0;
				}

				env->SetLongField(object, field, 0);
			}

			LeakDetection::Released(env, object);

			return handle;
		}

		void Release(JNIEnv * env, jlong handle, std::function<void(bool deferred)> release)
		{
			{
				Stripe & stripe = StripeOf(handle);
				std::lock_guard<std::mutex> lock(stripe.mutex);

				auto found = stripe.entries.find(handle);

				if (found != stripe.entries.end()) {
					found->second.releases.push_back(std::move(release));
					return;
				}
			}

			release(false);
		}

		void ThrowDisposed(JNIEnv * env, jobject object) noexcept
		{
			if (env->ExceptionCheck()) {
				return;
			}

			const NativeObjectClass & nativeObjectClass = GetNativeObjectClass(env);

			if (env->ExceptionCheck()) {
				return;
			}

			if (object != nullptr && nativeObjectClass.disposedException != nullptr &&
				env->IsInstanceOf(object, nativeObjectClass.cls))
			{
				jobject exception = env->CallObjectMethod(object, nativeObjectClass.disposedException);

				if (env->ExceptionCheck()) {
					return;
				}
				if (exception != nullptr) {
					env->Throw(static_cast<jthrowable>(exception));
					env->DeleteLocalRef(exception);
					return;
				}
			}

			ThrowJavaException(env, "java/lang/IllegalStateException", "Native object is disposed");
		}

		void ReportNotDeleted(bool deferred) noexcept
		{
			static const char * message = "Native object was not deleted. A reference is still around somewhere.";

			if (deferred) {
				RTC_LOG(LS_WARNING) << message;
				return;
			}

			ThrowJavaException(AttachCurrentThread(), "java/lang/Error", message);
		}
	}
}
