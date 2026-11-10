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
/* Header for class dev_onvoid_webrtc_media_recorder_FakeMedia */

#ifndef _Included_dev_onvoid_webrtc_media_recorder_FakeMedia
#define _Included_dev_onvoid_webrtc_media_recorder_FakeMedia
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    tableAddress
	 * Signature: ()J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_tableAddress
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    setClock
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_setClock
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    createFrames
	 * Signature: ()J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_createFrames
	(JNIEnv *, jclass);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    retainFrames
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_retainFrames
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    releaseFrames
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_releaseFrames
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    observers
	 * Signature: (J)I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_observers
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_recorder_FakeMedia
	 * Method:    push
	 * Signature: (JZLjava/lang/String;IIZIIJ[B)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_push
	(JNIEnv *, jclass, jlong, jboolean, jstring, jint, jint, jboolean, jint, jint, jlong, jbyteArray);

#ifdef __cplusplus
}
#endif
#endif
