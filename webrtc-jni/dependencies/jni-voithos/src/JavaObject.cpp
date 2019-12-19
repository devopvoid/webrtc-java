/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaObject.h"

namespace jni
{
	JavaObject::JavaObject(JNIEnv * env, const JavaRef<jobject> & obj) :
		env(env),
		obj(env, obj)
	{
	}

	jboolean JavaObject::getBoolean(jfieldID field)
	{
		return env->GetBooleanField(obj, field);
	}

	jbyte JavaObject::getByte(jfieldID field)
	{
		return env->GetByteField(obj, field);
	}

	jchar JavaObject::getChar(jfieldID field)
	{
		return env->GetCharField(obj, field);
	}

	jshort JavaObject::getShort(jfieldID field)
	{
		return env->GetShortField(obj, field);
	}

	jint JavaObject::getInt(jfieldID field)
	{
		return env->GetIntField(obj, field);
	}

	jlong JavaObject::getLong(jfieldID field)
	{
		return env->GetLongField(obj, field);
	}

	jfloat JavaObject::getFloat(jfieldID field)
	{
		return env->GetFloatField(obj, field);
	}

	jdouble JavaObject::getDouble(jfieldID field)
	{
		return env->GetDoubleField(obj, field);
	}

	JavaLocalRef<jobject> JavaObject::getObject(jfieldID field)
	{
		return JavaLocalRef<jobject>(env, env->GetObjectField(obj, field));
	}

	JavaLocalRef<jobjectArray> JavaObject::getObjectArray(jfieldID field)
	{
		return JavaLocalRef<jobjectArray>(env, static_cast<jobjectArray>(env->GetObjectField(obj, field)));
	}

	JavaLocalRef<jstring> JavaObject::getString(jfieldID field)
	{
		return JavaLocalRef<jstring>(env, static_cast<jstring>(env->GetObjectField(obj, field)));
	}
}