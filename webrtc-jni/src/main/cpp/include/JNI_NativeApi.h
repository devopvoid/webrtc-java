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

#include <jni.h>
/* Header for class dev_onvoid_webrtc_internal_NativeApi */

#ifndef _Included_dev_onvoid_webrtc_internal_NativeApi
#define _Included_dev_onvoid_webrtc_internal_NativeApi
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_internal_NativeApi
	 * Method:    tableAddress
	 * Signature: ()J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_tableAddress
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_internal_NativeApi
	 * Method:    version
	 * Signature: ()I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_version
	(JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
