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

	// Writes a code point as UTF-8, or as the modified UTF-8 of JNI, which
	// writes U+0000 as two bytes and one beyond U+FFFF as its two UTF-16
	// surrogates, three bytes each.
	void AppendCodePoint(std::string & out, char32_t c, bool modified)
	{
		if (modified && c > 0xFFFF) {
			c -= 0x10000;

			AppendCodePoint(out, 0xD800 + (c >> 10), true);
			AppendCodePoint(out, 0xDC00 + (c & 0x3FF), true);
		}
		else if (c < 0x80 && !(modified && c == 0)) {
			out += static_cast<char>(c);
		}
		else if (c < 0x800) {
			out += static_cast<char>(0xC0 | (c >> 6));
			out += static_cast<char>(0x80 | (c & 0x3F));
		}
		else if (c < 0x10000) {
			out += static_cast<char>(0xE0 | (c >> 12));
			out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
			out += static_cast<char>(0x80 | (c & 0x3F));
		}
		else {
			out += static_cast<char>(0xF0 | (c >> 18));
			out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
			out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
			out += static_cast<char>(0x80 | (c & 0x3F));
		}
	}

	// Reads the code point at `pos` and moves past it. What is not UTF-8 is
	// read as U+FFFD, one byte at a time.
	char32_t NextCodePoint(const std::string & text, size_t & pos) noexcept
	{
		const unsigned char lead = static_cast<unsigned char>(text[pos++]);

		if (lead < 0x80) {
			return lead;
		}

		size_t count;
		char32_t c;
		char32_t min;

		if ((lead & 0xE0) == 0xC0) {
			count = 1;
			c = lead & 0x1F;
			min = 0x80;
		}
		else if ((lead & 0xF0) == 0xE0) {
			count = 2;
			c = lead & 0x0F;
			min = 0x800;
		}
		else if ((lead & 0xF8) == 0xF0) {
			count = 3;
			c = lead & 0x07;
			min = 0x10000;
		}
		else {
			return 0xFFFD;
		}

		if (text.size() - pos < count) {
			return 0xFFFD;
		}

		for (size_t i = 0; i < count; i++) {
			const unsigned char next = static_cast<unsigned char>(text[pos + i]);

			if ((next & 0xC0) != 0x80) {
				return 0xFFFD;
			}

			c = (c << 6) | (next & 0x3F);
		}

		// Overlong, a surrogate, or beyond Unicode.
		if (c < min || (c >= 0xD800 && c <= 0xDFFF) || c > 0x10FFFF) {
			return 0xFFFD;
		}

		pos += count;

		return c;
	}

	std::string ToModifiedUtf8(const std::string & utf8)
	{
		std::string out;
		size_t pos = 0;

		out.reserve(utf8.size());

		while (pos < utf8.size()) {
			AppendCodePoint(out, NextCodePoint(utf8, pos), true);
		}

		return out;
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
		try {
			ThrowJavaException(env, "java/io/IOException", ToModifiedUtf8(message).c_str());
		}
		catch (const std::bad_alloc &) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Native memory allocation failed");
		}
	}

	jstring NewJavaString(JNIEnv * env, const std::string & utf8) noexcept
	{
		try {
			return env->NewStringUTF(ToModifiedUtf8(utf8).c_str());
		}
		catch (const std::bad_alloc &) {
			return nullptr;
		}
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

	void ReportPendingException(JNIEnv * env) noexcept
	{
		if (env->ExceptionCheck() == JNI_FALSE) {
			return;
		}

		LocalRef<jthrowable> exception(env, env->ExceptionOccurred());
		env->ExceptionClear();

		LocalRef<jclass> thread_class(env, env->FindClass("java/lang/Thread"));
		LocalRef<jclass> handler_class(env, env->FindClass("java/lang/Thread$UncaughtExceptionHandler"));

		if (env->ExceptionCheck() == JNI_TRUE) {
			env->ExceptionClear();
			return;
		}

		jmethodID current_thread = env->GetStaticMethodID(thread_class.get(), "currentThread", "()Ljava/lang/Thread;");
		jmethodID get_handler = env->GetMethodID(thread_class.get(), "getUncaughtExceptionHandler",
			"()Ljava/lang/Thread$UncaughtExceptionHandler;");
		jmethodID uncaught_exception = env->GetMethodID(handler_class.get(), "uncaughtException",
			"(Ljava/lang/Thread;Ljava/lang/Throwable;)V");

		if (env->ExceptionCheck() == JNI_TRUE) {
			env->ExceptionClear();
			return;
		}

		LocalRef<jobject> thread(env, env->CallStaticObjectMethod(thread_class.get(), current_thread));
		LocalRef<jobject> handler(env, env->CallObjectMethod(thread.get(), get_handler));

		if (env->ExceptionCheck() == JNI_FALSE && handler.get() != nullptr) {
			env->CallVoidMethod(handler.get(), uncaught_exception, thread.get(), exception.get());
		}

		// Like the JVM, ignore an exception thrown by the handler itself.
		if (env->ExceptionCheck() == JNI_TRUE) {
			env->ExceptionClear();
		}
	}

	JavaStringChars::JavaStringChars(JNIEnv * env, jstring string) noexcept :
		env_(env),
		string_(string),
		chars_(nullptr),
		length_(0)
	{
		if (string == nullptr) {
			ThrowJavaException(env, "java/lang/NullPointerException", "String is null");
			return;
		}

		// Null when the VM is out of memory, and it has thrown then.
		chars_ = env->GetStringChars(string, nullptr);

		if (chars_ != nullptr) {
			length_ = env->GetStringLength(string);
			held_strings.fetch_add(1);
		}
	}

	JavaStringChars::~JavaStringChars()
	{
		if (chars_ != nullptr) {
			env_->ReleaseStringChars(string_, chars_);
			held_strings.fetch_sub(1);
		}
	}

	std::string JavaStringChars::utf8() const
	{
		AllocationFault::CheckStringCopy();

		std::string out;

		out.reserve(static_cast<size_t>(length_));

		for (jsize i = 0; i < length_; i++) {
			char32_t c = chars_[i];

			if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length_
					&& chars_[i + 1] >= 0xDC00 && chars_[i + 1] <= 0xDFFF) {
				c = 0x10000 + ((c - 0xD800) << 10) + (chars_[++i] - 0xDC00);
			}
			else if (c >= 0xD800 && c <= 0xDFFF) {
				c = '?';
			}

			AppendCodePoint(out, c, false);
		}

		return out;
	}

	int JavaStringChars::Held() noexcept
	{
		return held_strings.load();
	}
}
