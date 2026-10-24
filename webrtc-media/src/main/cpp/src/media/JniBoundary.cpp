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

#include "media/JniBoundary.h"
#include "media/AllocationFault.h"

#include <atomic>
#include <cstddef>
#include <exception>
#include <new>

namespace
{
	std::atomic<int> held_strings{ 0 };

	// The text of a standard exception is in no defined encoding, and may be
	// in the locale's, while ThrowNew reads modified UTF-8. Printable ASCII is
	// both, so everything else is replaced. It goes into a buffer of fixed
	// size, because this may run when memory has run out.
	void CopyAscii(const char * text, char * buffer, size_t size) noexcept
	{
		size_t length = 0;

		while (text != nullptr && text[length] != '\0' && length + 1 < size) {
			const unsigned char c = static_cast<unsigned char>(text[length]);

			buffer[length] = (c >= 0x20 && c < 0x7F) ? static_cast<char>(c) : '?';
			length++;
		}

		buffer[length] = '\0';
	}
}

namespace ffmpeg
{
	void ThrowJavaException(JNIEnv * env, const char * class_name, const char * message) noexcept
	{
		if (env->ExceptionCheck()) {
			return;
		}

		jclass cls = env->FindClass(class_name);

		// Not found, the VM has thrown already.
		if (cls != nullptr) {
			env->ThrowNew(cls, message);
			env->DeleteLocalRef(cls);
		}
	}

	void ThrowIOException(JNIEnv * env, const std::string & message) noexcept
	{
		ThrowJavaException(env, "java/io/IOException", message.c_str());
	}

	void ThrowCurrentException(JNIEnv * env) noexcept
	{
		try {
			throw;
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Native memory allocation failed");
		}
		catch (const std::exception & e) {
			char message[256];

			CopyAscii(e.what(), message, sizeof(message));

			ThrowJavaException(env, "java/lang/RuntimeException", message);
		}
		catch (...) {
			ThrowJavaException(env, "java/lang/RuntimeException", "Unknown native exception");
		}
	}

	JavaStringChars::JavaStringChars(JNIEnv * env, jstring string) noexcept :
		env_(env),
		string_(string),
		chars_(nullptr)
	{
		if (string == nullptr) {
			ThrowJavaException(env, "java/lang/NullPointerException", "String is null");
			return;
		}

		// Null when the VM is out of memory, and it has thrown then.
		chars_ = env->GetStringUTFChars(string, nullptr);

		if (chars_ != nullptr) {
			held_strings.fetch_add(1);
		}
	}

	JavaStringChars::~JavaStringChars()
	{
		if (chars_ != nullptr) {
			env_->ReleaseStringUTFChars(string_, chars_);
			held_strings.fetch_sub(1);
		}
	}

	std::string JavaStringChars::str() const
	{
		AllocationFault::CheckStringCopy();

		return std::string(chars_);
	}

	int JavaStringChars::Held() noexcept
	{
		return held_strings.load();
	}
}
