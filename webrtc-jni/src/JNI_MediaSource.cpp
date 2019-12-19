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

#include "JNI_MediaSource.h"
#include "JavaEnums.h"
#include "JavaUtils.h"

#include "api/media_stream_interface.h"

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaSource_getState
(JNIEnv * env, jobject caller)
{
	webrtc::MediaSourceInterface * source = GetHandle<webrtc::MediaSourceInterface>(env, caller);
	CHECK_HANDLEV(source, nullptr);

	return jni::JavaEnums::toJava(env, source->state()).release();
}