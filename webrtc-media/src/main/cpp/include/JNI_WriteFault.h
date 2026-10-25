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
/* Header for class dev_onvoid_webrtc_media_recorder_WriteFault */

#ifndef _Included_dev_onvoid_webrtc_media_recorder_WriteFault
#define _Included_dev_onvoid_webrtc_media_recorder_WriteFault
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_WriteFault
	 * Method:    failHeader
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failHeader
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_WriteFault
	 * Method:    failPacket
	 * Signature: (I)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failPacket
	(JNIEnv *, jclass, jint);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_WriteFault
	 * Method:    failTrailer
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failTrailer
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_WriteFault
	 * Method:    failClose
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failClose
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_WriteFault
	 * Method:    disarm
	 * Signature: ()V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_disarm
	(JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
