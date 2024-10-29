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

#include "JNI_PowerManagement.h"
#include "WebRTCContext.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_PowerManagement_enableUserActivity
(JNIEnv * env, jobject caller)
{
    jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

	try {
		context->getPowerManagement()->enableUserActivity();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	fflush(NULL);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_PowerManagement_disableUserActivity
(JNIEnv * env, jobject caller)
{
    jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

	try {
		context->getPowerManagement()->disableUserActivity();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	fflush(NULL);
}