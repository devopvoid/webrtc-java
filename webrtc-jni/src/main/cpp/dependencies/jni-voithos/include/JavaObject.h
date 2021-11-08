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
			void setBoolean(jfieldID field, jboolean value);

			jbyte getByte(jfieldID field);
			void setByte(jfieldID field, jbyte value);

			jchar getChar(jfieldID field);
			void setChar(jfieldID field, jchar value);

			jshort getShort(jfieldID field);
			void setShort(jfieldID field, jshort value);

			jint getInt(jfieldID field);
			void setInt(jfieldID field, jint value);

			jlong getLong(jfieldID field);
			void setLong(jfieldID field, jlong value);

			jfloat getFloat(jfieldID field);
			void setFloat(jfieldID field, jfloat value);

			jdouble getDouble(jfieldID field);
			void setDouble(jfieldID field, jdouble value);

			JavaLocalRef<jobject> getObject(jfieldID field);
			void setObject(jfieldID field, JavaLocalRef<jobject> value);

			JavaLocalRef<jobjectArray> getObjectArray(jfieldID field);
			void setObjectArray(jfieldID field, JavaLocalRef<jobjectArray> value);

			JavaLocalRef<jstring> getString(jfieldID field);
			void setString(jfieldID field, JavaLocalRef<jstring> value);

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