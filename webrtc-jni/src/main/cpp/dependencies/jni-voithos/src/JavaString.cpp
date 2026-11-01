/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaArray.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include <new>
#include <vector>

namespace jni
{
	namespace
	{
		constexpr jchar kReplacementChar = 0xFFFD;

		bool IsContinuation(unsigned char byte, unsigned char low = 0x80, unsigned char high = 0xBF)
		{
			return byte >= low && byte <= high;
		}

		void AppendCodePoint(std::vector<jchar> & out, char32_t cp)
		{
			if (cp < 0x10000) {
				out.push_back(static_cast<jchar>(cp));
			}
			else {
				cp -= 0x10000;
				out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
				out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
			}
		}

		// Standard UTF-8 to UTF-16. JNI's NewStringUTF takes Modified UTF-8,
		// which encodes U+0000 and supplementary characters differently and
		// mangles what WebRTC hands out. Every ill-formed sequence becomes one
		// U+FFFD per maximal subpart (Unicode 3.9, Table 3-7), as Java's own
		// UTF-8 decoder does.
		std::vector<jchar> DecodeUtf8(const std::string & str)
		{
			const auto * bytes = reinterpret_cast<const unsigned char *>(str.data());
			const size_t length = str.size();

			std::vector<jchar> out;
			out.reserve(length);

			size_t i = 0;

			while (i < length) {
				const unsigned char lead = bytes[i];

				if (lead < 0x80) {
					out.push_back(lead);
					i++;
					continue;
				}

				size_t count;
				char32_t cp;
				unsigned char low = 0x80;
				unsigned char high = 0xBF;

				if (lead >= 0xC2 && lead <= 0xDF) {
					count = 1;
					cp = lead & 0x1F;
				}
				else if (lead >= 0xE0 && lead <= 0xEF) {
					count = 2;
					cp = lead & 0x0F;
					low = lead == 0xE0 ? 0xA0 : 0x80;
					high = lead == 0xED ? 0x9F : 0xBF;   // No surrogates.
				}
				else if (lead >= 0xF0 && lead <= 0xF4) {
					count = 3;
					cp = lead & 0x07;
					low = lead == 0xF0 ? 0x90 : 0x80;
					high = lead == 0xF4 ? 0x8F : 0xBF;   // Nothing above U+10FFFF.
				}
				else {
					out.push_back(kReplacementChar);
					i++;
					continue;
				}

				size_t consumed = 1;

				while (consumed <= count && i + consumed < length) {
					const unsigned char next = bytes[i + consumed];

					// Only the first continuation byte has a narrowed range.
					if (consumed == 1 ? !IsContinuation(next, low, high) : !IsContinuation(next)) {
						break;
					}

					cp = (cp << 6) | (next & 0x3F);
					consumed++;
				}

				if (consumed == count + 1) {
					AppendCodePoint(out, cp);
				}
				else {
					out.push_back(kReplacementChar);
				}

				i += consumed;
			}

			return out;
		}

		// Standard UTF-16 to UTF-8. An unpaired surrogate becomes '?', as with
		// String.getBytes(UTF_8).
		std::string EncodeUtf8(const std::vector<jchar> & chars)
		{
			std::string out;
			out.reserve(chars.size());

			for (size_t i = 0; i < chars.size(); i++) {
				char32_t cp = chars[i];

				if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < chars.size() && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
					cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
					i++;
				}
				else if (cp >= 0xD800 && cp <= 0xDFFF) {
					cp = '?';
				}

				if (cp < 0x80) {
					out.push_back(static_cast<char>(cp));
				}
				else if (cp < 0x800) {
					out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
					out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
				}
				else if (cp < 0x10000) {
					out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
					out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
					out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
				}
				else {
					out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
					out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
					out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
					out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
				}
			}

			return out;
		}
	}

	JavaString::JavaString(JNIEnv * env)
	{
		cls = FindClass(env, "java/lang/String");
	}

	std::string JavaString::toNative(JNIEnv * env, const JavaRef<jstring> & jstr)
	{
		if (jstr.get() == nullptr) {
			return "";
		}

		const jsize length = env->GetStringLength(jstr);

		std::vector<jchar> chars(static_cast<size_t>(length));

		if (length > 0) {
			env->GetStringRegion(jstr, 0, length, chars.data());
		}

		return EncodeUtf8(chars);
	}

	JavaLocalRef<jstring> JavaString::toJava(JNIEnv * env, const std::string & str)
	{
		static const jchar empty = 0;

		std::vector<jchar> chars;

		try {
			chars = DecodeUtf8(str);
		}
		catch (const std::bad_alloc &) {
			// Fail the JNI way, so that callers in noexcept code need not catch.
			JavaLocalRef<jclass> error(env, env->FindClass("java/lang/OutOfMemoryError"));

			if (error.get() != nullptr) {
				env->ThrowNew(error.get(), "Converting a native string failed");
			}

			return nullptr;
		}

		const jchar * data = chars.empty() ? &empty : chars.data();

		// A null result means an OutOfMemoryError is pending.
		return JavaLocalRef<jstring>(env, env->NewString(data, static_cast<jsize>(chars.size())));
	}

	JavaLocalRef<jstring> JavaString::toJavaOrNull(JNIEnv * env, const std::string & str)
	{
		if (str.empty()) {
			return nullptr;
		}

		return toJava(env, str);
	}

	JavaLocalRef<jobjectArray> JavaString::createArray(JNIEnv * env, const std::vector<std::string> & vector)
	{
		const auto javaClass = JavaClasses::get<JavaString>(env);

		return JavaArray::createObjectArray(env, vector, javaClass->cls, &toJava);
	}
}
