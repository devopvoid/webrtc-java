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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_CODEC_INFO_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_CODEC_INFO_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video_codecs/sdp_video_format.h"

#include <jni.h>

#include <vector>

namespace jni
{
	namespace VideoCodecInfo
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SdpVideoFormat & format);
		webrtc::SdpVideoFormat toNative(JNIEnv * env, const JavaRef<jobject> & info);

		JavaLocalRef<jobjectArray> toJavaArray(JNIEnv * env, const std::vector<webrtc::SdpVideoFormat> & formats);

		// Converts a java.util.List of VideoCodecInfos, skipping null
		// elements.
		std::vector<webrtc::SdpVideoFormat> toNativeList(JNIEnv * env, const JavaRef<jobject> & list);

		class JavaVideoCodecInfoClass : public JavaClass
		{
			public:
				explicit JavaVideoCodecInfoClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
				jfieldID name;
				jfieldID parameters;
				jfieldID scalabilityModes;

				jmethodID listToArray;
		};
	}
}

#endif
