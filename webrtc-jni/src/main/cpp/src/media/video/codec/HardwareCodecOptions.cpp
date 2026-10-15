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

#include "media/video/codec/HardwareCodecOptions.h"
#include "JavaUtils.h"

#include <jni.h>

namespace jni
{
	namespace
	{
		// Whether the Java system property is "true", as Boolean.getBoolean has
		// it; false where Java cannot be asked.
		bool JavaBooleanProperty(const char * property)
		{
			JNIEnv * env = AttachCurrentThread();

			// An exception pending is someone else's to handle, and no JNI call
			// may be made meanwhile.
			if (env == nullptr || env->ExceptionCheck()) {
				return false;
			}
			if (env->PushLocalFrame(4) != JNI_OK) {
				env->ExceptionClear();
				return false;
			}

			bool enabled = false;

			// java.lang.Boolean comes from the bootstrap class loader, which
			// any thread reaches, one without Java frames as well.
			jclass booleanClass = env->FindClass("java/lang/Boolean");

			if (booleanClass != nullptr) {
				jmethodID getBoolean = env->GetStaticMethodID(booleanClass, "getBoolean", "(Ljava/lang/String;)Z");
				jstring name = getBoolean != nullptr ? env->NewStringUTF(property) : nullptr;

				if (name != nullptr) {
					enabled = env->CallStaticBooleanMethod(booleanClass, getBoolean, name) == JNI_TRUE;
				}
			}

			// Raised here, so cleared here.
			if (env->ExceptionCheck()) {
				env->ExceptionClear();
				enabled = false;
			}

			env->PopLocalFrame(nullptr);

			return enabled;
		}
	}

	bool IsHardwareVp9EncoderEnabled()
	{
		return JavaBooleanProperty(kHardwareVp9EncoderProperty);
	}
}
