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
/* Header for class dev_onvoid_webrtc_internal_AllocationFault */

#ifndef _Included_dev_onvoid_webrtc_internal_AllocationFault
#define _Included_dev_onvoid_webrtc_internal_AllocationFault
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_internal_AllocationFault
	 * Method:    failNextListener
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextListener
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_internal_AllocationFault
	 * Method:    failNextEncodedObserver
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextEncodedObserver
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_internal_AllocationFault
	 * Method:    addEncodedObserver
	 * Signature: (J)J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_addEncodedObserver
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_internal_AllocationFault
	 * Method:    removeEncodedObserver
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_removeEncodedObserver
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_internal_AllocationFault
	 * Method:    encodedObservers
	 * Signature: (J)I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_encodedObservers
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_internal_AllocationFault
	 * Method:    disarm
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_disarm
	(JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
