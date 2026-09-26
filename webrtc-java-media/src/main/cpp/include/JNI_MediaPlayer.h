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
/* Header for class dev_onvoid_webrtc_media_player_MediaPlayer */

#ifndef _Included_dev_onvoid_webrtc_media_player_MediaPlayer
#define _Included_dev_onvoid_webrtc_media_player_MediaPlayer
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    create
	 * Signature: (JJJJ)J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_create
	(JNIEnv *, jobject, jlong, jlong, jlong, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    start
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_start
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    suspend
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_suspend
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    seek
	 * Signature: (JJ)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_seek
	(JNIEnv *, jclass, jlong, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    setLooping
	 * Signature: (JZ)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_setLooping
	(JNIEnv *, jclass, jlong, jboolean);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    position
	 * Signature: (J)J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_position
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    state
	 * Signature: (J)I
	 */
	JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_state
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_player_MediaPlayer
	 * Method:    dispose
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_dispose
	(JNIEnv *, jclass, jlong);

#ifdef __cplusplus
}
#endif
#endif
