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

#include "JNI_HardwareVideoEncoderFactory.h"
#include "media/video/codec/DefaultVideoCodecFactories.h"
#include "media/video/codec/VideoCodecInfo.h"
#include "JavaUtils.h"

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_media_video_codec_HardwareVideoEncoderFactory_getCodecsInternal
(JNIEnv * env, jclass caller)
{
	try {
		// One factory for both lists, as making one probes the hardware.
		auto factory = jni::CreateHardwareVideoEncoderFactory();

		auto supported = jni::VideoCodecInfo::toJavaArray(env, factory->GetSupportedFormats());
		auto hardware = jni::VideoCodecInfo::toJavaArray(env, jni::GetHardwareVideoEncoderFormats(*factory));
		auto arrayClass = jni::JavaLocalRef<jclass>(env, env->GetObjectClass(supported.get()));

		jobjectArray codecs = env->NewObjectArray(2, arrayClass.get(), nullptr);

		ExceptionCheck(env);

		env->SetObjectArrayElement(codecs, 0, supported.get());
		env->SetObjectArrayElement(codecs, 1, hardware.get());

		return codecs;
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return nullptr;
}
