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

#include "JNI_NativeVideoEncoderCallback.h"
#include "media/video/codec/VideoEncoderWrapper.h"
#include "JavaUtils.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_codec_NativeVideoEncoderCallback_onEncodedFrame
(JNIEnv * env, jclass caller, jlong handle, jobject image)
{
	// The Java callback holds its lock and has checked the handle, so the
	// wrapper cannot be released meanwhile.
	auto encoder = reinterpret_cast<jni::VideoEncoderWrapper *>(handle);

	try {
		encoder->OnEncodedFrame(env, image);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}
