/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_STRING_H_
#define JNI_JAVA_STRING_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <string>
#include <vector>

namespace jni
{
	class JavaString : public JavaClass
	{
		public:
			explicit JavaString(JNIEnv * env);

			// UTF-16 to UTF-8; null becomes "".
			static std::string toNative(JNIEnv * env, const JavaRef<jstring> & jstr);
			// UTF-8 to UTF-16: "" stays "", U+0000 and characters beyond the
			// BMP survive. Throws no C++ exception; null with a pending Java
			// exception if the string cannot be made.
			static JavaLocalRef<jstring> toJava(JNIEnv * env, const std::string & str);
			// For values that WebRTC leaves empty when they are absent and that
			// Java reports as null.
			static JavaLocalRef<jstring> toJavaOrNull(JNIEnv * env, const std::string & str);
			static JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const std::vector<std::string> & vector);

		private:
			jclass cls;
	};
}

#endif