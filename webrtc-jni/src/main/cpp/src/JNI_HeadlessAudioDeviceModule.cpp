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

#include "JNI_HeadlessAudioDeviceModule.h"
#include "Exception.h"
#include "JavaError.h"
#include "JavaUtils.h"
#include "WebRTCContext.h"

#include "api/HeadlessAudioDeviceModule.h"

#include "api/scoped_refptr.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_HeadlessAudioDeviceModule_initialize
(JNIEnv* env, jobject caller)
{
	jni::WebRTCContext * context = static_cast<jni::WebRTCContext*>(javaContext);
	webrtc::scoped_refptr<jni::HeadlessAudioDeviceModule> audioModule = jni::HeadlessAudioDeviceModule::Create(context->webrtcEnv);

	if (!audioModule) {
		env->Throw(jni::JavaError(env, "Create HeadlessAudioDeviceModule failed"));
		return;
	}

	if (audioModule->Init() != 0) {
		env->Throw(jni::JavaError(env, "Initialize HeadlessAudioDeviceModule failed"));
		return;
	}

	SetHandle(env, caller, audioModule.release());
}
