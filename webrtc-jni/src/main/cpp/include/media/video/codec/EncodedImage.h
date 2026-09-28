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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_ENCODED_IMAGE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_ENCODED_IMAGE_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video/encoded_image.h"

#include <jni.h>

#include <cstdint>

namespace jni
{
	namespace EncodedImage
	{
		// Wraps the image for a Java decoder. The payload is not copied: the
		// Java buffer is read-only and points into the image, so it is valid
		// only for as long as the image is.
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::EncodedImage & image);

		// Converts the image a Java encoder produced, copying its payload.
		webrtc::EncodedImage toNative(JNIEnv * env, const JavaRef<jobject> & image);

		int64_t getCaptureTimeNs(JNIEnv * env, const JavaRef<jobject> & image);

		class JavaEncodedImageClass : public JavaClass
		{
			public:
				explicit JavaEncodedImageClass(JNIEnv * env);

				jclass cls;
				jmethodID fromNative;
				jmethodID getDirectPayload;
				jmethodID getFrameTypeIndex;
				jmethodID getQpOrUnknown;
				jfieldID encodedWidth;
				jfieldID encodedHeight;
				jfieldID captureTimeNs;
				jfieldID rotation;
		};
	}
}

#endif
