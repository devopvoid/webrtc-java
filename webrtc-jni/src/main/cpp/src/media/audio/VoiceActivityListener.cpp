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

#include "media/audio/VoiceActivityListener.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "rtc_base/system/arch.h"

namespace jni
{
	namespace
	{
#if defined(WEBRTC_ARCH_X86_FAMILY)
		// Flush-to-zero and denormals-are-zero, in MXCSR.
		constexpr int kDenormalBits = 0x8040;
#elif defined(WEBRTC_ARCH_ARM_FAMILY)
		// Flush-to-zero, in FPCR or FPSCR.
		constexpr int kDenormalBits = 1 << 24;
#else
		constexpr int kDenormalBits = 0;
#endif

		int ReadControlWord()
		{
			int word = 0;
#if defined(WEBRTC_ARCH_X86_FAMILY)
			asm volatile("stmxcsr %0" : "=m"(word));
#elif defined(WEBRTC_ARCH_ARM_FAMILY) && defined(WEBRTC_ARCH_32_BITS)
			asm volatile("vmrs %[word], FPSCR" : [word] "=r"(word));
#elif defined(WEBRTC_ARCH_ARM_FAMILY) && defined(WEBRTC_ARCH_64_BITS)
			asm volatile("mrs %x[word], FPCR" : [word] "=r"(word));
#endif
			return word;
		}

		void WriteControlWord(int word)
		{
#if defined(WEBRTC_ARCH_X86_FAMILY)
			asm volatile("ldmxcsr %0" : : "m"(word));
#elif defined(WEBRTC_ARCH_ARM_FAMILY) && defined(WEBRTC_ARCH_32_BITS)
			asm volatile("vmsr FPSCR, %[word]" : : [word] "r"(word));
#elif defined(WEBRTC_ARCH_ARM_FAMILY) && defined(WEBRTC_ARCH_64_BITS)
			asm volatile("msr FPCR, %x[word]" : : [word] "r"(word));
#endif
		}

		// Undoes webrtc::DenormalDisabler for a call into Java. AudioProcessing
		// holds one while it hands its audio to the capture tap, and the JVM
		// needs its default floating point state: Java's arithmetic depends on
		// it, and on Windows the JVM does not restore it by itself, where
		// -Xcheck:jni ends the process on the changed state.
		class ScopedDenormals
		{
			public:
				ScopedDenormals() :
					saved(ReadControlWord())
				{
					if (saved & kDenormalBits) {
						WriteControlWord(saved & ~kDenormalBits);
					}
				}

				~ScopedDenormals()
				{
					if (saved & kDenormalBits) {
						WriteControlWord(saved);
					}
				}

				ScopedDenormals(const ScopedDenormals &) = delete;
				ScopedDenormals & operator=(const ScopedDenormals &) = delete;

			private:
				const int saved;
		};
	}

	VoiceActivityListener::VoiceActivityListener(JNIEnv * env, const JavaGlobalRef<jobject> & listener,
		float threshold, int hangoverMs) :
		listener(listener),
		javaClass(JavaClasses::get<JavaVoiceActivityListenerClass>(env)),
		threshold(threshold),
		hangoverChunks(hangoverMs / 10 + (hangoverMs % 10 != 0)),
		quietChunks(0),
		speaking(false)
	{
	}

	void VoiceActivityListener::Update(float probability)
	{
		if (probability >= threshold) {
			quietChunks = 0;

			if (speaking) {
				return;
			}

			speaking = true;
		}
		else if (speaking && ++quietChunks > hangoverChunks) {
			quietChunks = 0;
			speaking = false;
		}
		else {
			return;
		}

		// Attaching the thread runs Java as well.
		ScopedDenormals denormals;

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		env->CallVoidMethod(listener, javaClass->onVoiceActivity, static_cast<jboolean>(speaking));

		ReportPendingException(env);
	}

	VoiceActivityListener::JavaVoiceActivityListenerClass::JavaVoiceActivityListenerClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_AUDIO"VoiceActivityListener");

		onVoiceActivity = GetMethod(env, cls, "onVoiceActivity", "(Z)V");
	}
}
