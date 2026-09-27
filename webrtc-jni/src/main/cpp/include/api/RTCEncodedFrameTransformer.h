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

#ifndef JNI_WEBRTC_API_RTC_ENCODED_FRAME_TRANSFORMER_H_
#define JNI_WEBRTC_API_RTC_ENCODED_FRAME_TRANSFORMER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/frame_transformer_interface.h"
#include "api/media_types.h"

#include <jni.h>

#include <memory>
#include <string>
#include <unordered_map>

namespace jni
{
	// Runs a Java RTCEncodedFrameTransformer on encoded frames.
	//
	// Each frame is shown to Java as an RTCEncodedAudioFrame or
	// RTCEncodedVideoFrame carrying its metadata and the address of the
	// native frame. The payload is not copied up front: the Java frame copies
	// it only when asked for it, and only for as long as the transform runs.
	class RTCEncodedFrameTransformer
	{
		public:
			// Resolves the Java classes on the calling thread, which has to be
			// one the application called in on, so that the worker running
			// the transform never needs a class loader.
			RTCEncodedFrameTransformer(JNIEnv * env, jobject transformer);
			~RTCEncodedFrameTransformer() = default;

			RTCEncodedFrameTransformer(const RTCEncodedFrameTransformer &) = delete;
			RTCEncodedFrameTransformer & operator=(const RTCEncodedFrameTransformer &) = delete;

			// Runs the Java transform on the frame, which it may change in
			// place. Returns whether the frame is to be sent on: false if the
			// transform dropped it, or if it could not be run at all, since
			// sending a frame untransformed could send in the clear what an
			// encryption transform was meant to protect.
			//
			// Must be called on a thread attached to the JVM, and always on
			// the same one.
			bool Transform(JNIEnv * env, webrtc::TransformableFrameInterface & frame,
				webrtc::MediaType mediaType);

		private:
			jstring MimeType(JNIEnv * env, const std::string & mimeType);

			jobject NewVideoFrame(JNIEnv * env, webrtc::TransformableVideoFrameInterface & frame);
			jobject NewAudioFrame(JNIEnv * env, webrtc::TransformableAudioFrameInterface & frame);

		private:
			class JavaEncodedFrameClass : public JavaClass
			{
				public:
					explicit JavaEncodedFrameClass(JNIEnv * env);

					jclass cls;
					jmethodID dispatch;
			};

			class JavaEncodedVideoFrameClass : public JavaClass
			{
				public:
					explicit JavaEncodedVideoFrameClass(JNIEnv * env);

					jclass cls;
					jmethodID ctor;
			};

			class JavaEncodedAudioFrameClass : public JavaClass
			{
				public:
					explicit JavaEncodedAudioFrameClass(JNIEnv * env);

					jclass cls;
					jmethodID ctor;
			};

		private:
			JavaGlobalRef<jobject> transformer;

			const std::shared_ptr<JavaEncodedFrameClass> javaFrameClass;
			const std::shared_ptr<JavaEncodedVideoFrameClass> javaVideoFrameClass;
			const std::shared_ptr<JavaEncodedAudioFrameClass> javaAudioFrameClass;

			// A stream carries one or two codecs, so the MIME type strings are
			// made once rather than for every frame.
			std::unordered_map<std::string, std::unique_ptr<JavaGlobalRef<jstring>>> mimeTypes;
	};
}

#endif
