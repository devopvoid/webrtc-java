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

	void JavaObject::setBoolean(jfieldID field, jboolean value)
	{
		env->SetBooleanField(obj, field, value);
	}

	jbyte JavaObject::getByte(jfieldID field)
	{
		return env->GetByteField(obj, field);
	}

	void JavaObject::setByte(jfieldID field, jbyte value)
	{
		env->SetByteField(obj, field, value);
	}

	jchar JavaObject::getChar(jfieldID field)
	{
		return env->GetCharField(obj, field);
	}

	void JavaObject::setChar(jfieldID field, jchar value)
	{
		env->SetCharField(obj, field, value);
	}

	jshort JavaObject::getShort(jfieldID field)
	{
		return env->GetShortField(obj, field);
	}

	void JavaObject::setShort(jfieldID field, jshort value)
	{
		env->SetShortField(obj, field, value);
	}

	jint JavaObject::getInt(jfieldID field)
	{
		return env->GetIntField(obj, field);
	}

	void JavaObject::setInt(jfieldID field, jint value)
	{
		env->SetIntField(obj, field, value);
	}

	jlong JavaObject::getLong(jfieldID field)
	{
		return env->GetLongField(obj, field);
	}

	void JavaObject::setLong(jfieldID field, jlong value)
	{
		env->SetLongField(obj, field, value);
	}

	jfloat JavaObject::getFloat(jfieldID field)
	{
		return env->GetFloatField(obj, field);
	}

	void JavaObject::setFloat(jfieldID field, jfloat value)
	{
		env->SetFloatField(obj, field, value);
	}

	jdouble JavaObject::getDouble(jfieldID field)
	{
		return env->GetDoubleField(obj, field);
	}

	void JavaObject::setDouble(jfieldID field, jdouble value)
	{
		env->SetDoubleField(obj, field, value);
	}

	JavaLocalRef<jobject> JavaObject::getObject(jfieldID field)
	{
		return JavaLocalRef<jobject>(env, env->GetObjectField(obj, field));
	}

	void JavaObject::setObject(jfieldID field, JavaLocalRef<jobject> value)
	{
		env->SetObjectField(obj, field, value.get());
	}

	JavaLocalRef<jobjectArray> JavaObject::getObjectArray(jfieldID field)
	{
		return JavaLocalRef<jobjectArray>(env, static_cast<jobjectArray>(env->GetObjectField(obj, field)));
	}

	void JavaObject::setObjectArray(jfieldID field, JavaLocalRef<jobjectArray> value)
	{
		env->SetObjectField(obj, field, value.get());
	}

	JavaLocalRef<jstring> JavaObject::getString(jfieldID field)
	{
		return JavaLocalRef<jstring>(env, static_cast<jstring>(env->GetObjectField(obj, field)));
	}

	void JavaObject::setString(jfieldID field, JavaLocalRef<jstring> value)
	{
		env->SetObjectField(obj, field, value.get());
	}
}