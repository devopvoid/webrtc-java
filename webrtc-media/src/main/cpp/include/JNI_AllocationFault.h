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
/* Header for class dev_onvoid_webrtc_media_player_AllocationFault */

#ifndef _Included_dev_onvoid_webrtc_media_player_AllocationFault
#define _Included_dev_onvoid_webrtc_media_player_AllocationFault
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    failNextStringCopy
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failNextStringCopy
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    failNextAllocation
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failNextAllocation
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    failNextThreadStart
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failNextThreadStart
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    failWriterAllocation
	 * Signature: (I)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failWriterAllocation
	(JNIEnv *, jclass, jint);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    isArmed
	 * Signature: ()Z
	 */
	JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_isArmed
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    throwInNative
	 * Signature: (ILjava/lang/String;)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_throwInNative
	(JNIEnv *, jclass, jint, jstring);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    disarm
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_disarm
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_AllocationFault
	 * Method:    heldStrings
	 * Signature: ()I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_heldStrings
	(JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
