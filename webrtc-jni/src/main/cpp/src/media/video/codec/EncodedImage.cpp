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

#include "media/video/codec/EncodedImage.h"
#include "Exception.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/video/video_frame_type.h"
#include "api/video/video_rotation.h"
#include "rtc_base/time_utils.h"

namespace jni
{
	namespace EncodedImage
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::EncodedImage & image)
		{
			const auto javaClass = JavaClasses::get<JavaEncodedImageClass>(env);

			// NewDirectByteBuffer does not accept an empty region at a null
			// address, which an empty image may have.
			static uint8_t empty = 0;
			uint8_t * data = image.size() > 0 ? const_cast<uint8_t *>(image.data()) : &empty;

			JavaLocalRef<jobject> buffer(env, env->NewDirectByteBuffer(data, static_cast<jlong>(image.size())));

			ExceptionCheck(env);

			jobject jImage = env->CallStaticObjectMethod(javaClass->cls, javaClass->fromNative, buffer.get(),
				static_cast<jint>(image._encodedWidth),
				static_cast<jint>(image._encodedHeight),
				static_cast<jlong>(image.capture_time_ms_ * webrtc::kNumNanosecsPerMillisec),
				static_cast<jint>(image.frame_type()),
				static_cast<jint>(image.rotation_),
				static_cast<jint>(image.qp_));

			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, jImage);
		}

		webrtc::EncodedImage toNative(JNIEnv * env, const JavaRef<jobject> & image)
		{
			const auto javaClass = JavaClasses::get<JavaEncodedImageClass>(env);

			JavaLocalRef<jobject> payload(env, env->CallObjectMethod(image, javaClass->getDirectPayload));

			ExceptionCheck(env);

			const uint8_t * data = static_cast<const uint8_t *>(env->GetDirectBufferAddress(payload.get()));
			const jlong size = env->GetDirectBufferCapacity(payload.get());

			if (size < 0 || (data == nullptr && size > 0)) {
				throw Exception("The EncodedImage buffer cannot be read");
			}

			jint frameType = env->CallIntMethod(image, javaClass->getFrameTypeIndex);
			ExceptionCheck(env);

			jint qp = env->CallIntMethod(image, javaClass->getQpOrUnknown);
			ExceptionCheck(env);

			JavaObject obj(env, image);

			webrtc::EncodedImage nativeImage;
			nativeImage.SetEncodedData(webrtc::EncodedImageBuffer::Create(data, static_cast<size_t>(size)));
			nativeImage._encodedWidth = static_cast<uint32_t>(obj.getInt(javaClass->encodedWidth));
			nativeImage._encodedHeight = static_cast<uint32_t>(obj.getInt(javaClass->encodedHeight));
			nativeImage.rotation_ = static_cast<webrtc::VideoRotation>(obj.getInt(javaClass->rotation));
			nativeImage.qp_ = qp;
			nativeImage.set_frame_type(static_cast<webrtc::VideoFrameType>(frameType));

			return nativeImage;
		}

		int64_t getCaptureTimeNs(JNIEnv * env, const JavaRef<jobject> & image)
		{
			const auto javaClass = JavaClasses::get<JavaEncodedImageClass>(env);

			return env->GetLongField(image, javaClass->captureTimeNs);
		}

		JavaEncodedImageClass::JavaEncodedImageClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_CODEC"EncodedImage");

			fromNative = GetStaticMethod(env, cls, "fromNative",
				"(" BYTE_BUFFER_SIG "IIJIII)L" PKG_CODEC "EncodedImage;");
			getDirectPayload = GetMethod(env, cls, "getDirectPayload", "()" BYTE_BUFFER_SIG);
			getFrameTypeIndex = GetMethod(env, cls, "getFrameTypeIndex", "()I");
			getQpOrUnknown = GetMethod(env, cls, "getQpOrUnknown", "()I");

			encodedWidth = GetFieldID(env, cls, "encodedWidth", "I");
			encodedHeight = GetFieldID(env, cls, "encodedHeight", "I");
			captureTimeNs = GetFieldID(env, cls, "captureTimeNs", "J");
			rotation = GetFieldID(env, cls, "rotation", "I");
		}
	}
}
