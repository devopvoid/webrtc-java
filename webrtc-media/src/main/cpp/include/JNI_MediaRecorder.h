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
/* Header for class dev_onvoid_webrtc_media_recorder_MediaRecorder */

#ifndef _Included_dev_onvoid_webrtc_media_recorder_MediaRecorder
#define _Included_dev_onvoid_webrtc_media_recorder_MediaRecorder
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_MediaRecorder
	 * Method:    create
	 * Signature: (Ljava/lang/String;J)J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_create
	(JNIEnv *, jobject, jstring, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_MediaRecorder
	 * Method:    addTrack
	 * Signature: (JJ)I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_addTrack
	(JNIEnv *, jclass, jlong, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_MediaRecorder
	 * Method:    start
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_start
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_MediaRecorder
	 * Method:    stop
	 * Signature: (J)Z
	 */
	JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_stop
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_MediaRecorder
	 * Method:    dispose
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_dispose
	(JNIEnv *, jclass, jlong);

#ifdef __cplusplus
}
#endif
#endif
