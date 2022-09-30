/*
 * Copyright 2019 Alex Andres
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

#include "api/DataBufferFactory.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	DataBufferFactory::DataBufferFactory(JNIEnv * env, const char * className) :
		JavaFactory(env, className, "(" BYTE_BUFFER_SIG "Z)V")
	{
	}

	JavaLocalRef<jobject> DataBufferFactory::create(JNIEnv * env, const webrtc::DataBuffer * dataBuffer) const
	{
		jobject directBuffer = env->NewDirectByteBuffer(const_cast<char *>(dataBuffer->data.data<char>()), dataBuffer->data.size());
		const jboolean isBinary = static_cast<jboolean>(dataBuffer->binary);

		jobject object = env->NewObject(javaClass, javaCtor, directBuffer, isBinary);
		ExceptionCheck(env);

		return JavaLocalRef<jobject>(env, object);
	}
}