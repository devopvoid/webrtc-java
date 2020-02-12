/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_OBJECT_H_
#define JNI_JAVA_OBJECT_H_

#include "JavaRef.h"

#include <jni.h>
#include <type_traits>

namespace jni
{
	class JavaObject
	{
		public:
			JavaObject(JNIEnv * env, const JavaRef<jobject> & obj);
			~JavaObject() = default;

			jboolean getBoolean(jfieldID field);
			jbyte getByte(jfieldID field);
			jchar getChar(jfieldID field);
			jshort getShort(jfieldID field);
			jint getInt(jfieldID field);
			jlong getLong(jfieldID field);
			jfloat getFloat(jfieldID field);
			jdouble getDouble(jfieldID field);
			JavaLocalRef<jobject> getObject(jfieldID field);
			JavaLocalRef<jobjectArray> getObjectArray(jfieldID field);
			JavaLocalRef<jstring> getString(jfieldID field);

			template <typename T, typename = std::enable_if_t<std::is_integral<T>::value>>
			T getInt(jfieldID field)
			{
				return static_cast<T>(env->GetIntField(obj, field));
			}

		protected:
			JNIEnv * env;
			JavaLocalRef<jobject> obj;
	};
}

#endif