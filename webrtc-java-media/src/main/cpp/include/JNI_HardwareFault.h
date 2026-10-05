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
/* Header for class dev_onvoid_webrtc_media_player_HardwareFault */

#ifndef _Included_dev_onvoid_webrtc_media_player_HardwareFault
#define _Included_dev_onvoid_webrtc_media_player_HardwareFault
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_player_HardwareFault
	 * Method:    failSend
	 * Signature: (ZI)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_failSend
	(JNIEnv *, jclass, jboolean, jint);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_HardwareFault
	 * Method:    failDrain
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_failDrain
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_HardwareFault
	 * Method:    disarm
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_disarm
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_HardwareFault
	 * Method:    softwareThreads
	 * Signature: ()I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_softwareThreads
	(JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
