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
/* Header for class dev_onvoid_webrtc_media_ffmpeg_MediaReader */

#ifndef _Included_dev_onvoid_webrtc_media_ffmpeg_MediaReader
#define _Included_dev_onvoid_webrtc_media_ffmpeg_MediaReader
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_media_ffmpeg_MediaReader
	 * Method:    open
	 * Signature: (Ljava/lang/String;)J
	 */
	JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_ffmpeg_MediaReader_open
	(JNIEnv *, jclass, jstring);

	/*
	 * Class:     dev_onvoid_webrtc_media_ffmpeg_MediaReader
	 * Method:    info
	 * Signature: (J)Ldev/onvoid/webrtc/media/ffmpeg/MediaInfo;
	 */
	JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_ffmpeg_MediaReader_info
	(JNIEnv *, jclass, jlong);

	/*
	 * Class:     dev_onvoid_webrtc_media_ffmpeg_MediaReader
	 * Method:    dispose
	 * Signature: (J)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_ffmpeg_MediaReader_dispose
	(JNIEnv *, jclass, jlong);

#ifdef __cplusplus
}
#endif
#endif
