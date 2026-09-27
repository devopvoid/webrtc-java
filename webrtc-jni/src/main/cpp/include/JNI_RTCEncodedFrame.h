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
/* Header for class dev_onvoid_webrtc_RTCEncodedFrame */

#ifndef _Included_dev_onvoid_webrtc_RTCEncodedFrame
#define _Included_dev_onvoid_webrtc_RTCEncodedFrame
#ifdef __cplusplus
extern "C" {
#endif
	/*
	 * Class:     dev_onvoid_webrtc_RTCEncodedFrame
	 * Method:    copyData
	 * Signature: (JLjava/nio/ByteBuffer;)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedFrame_copyData
	(JNIEnv *, jclass, jlong, jobject);

	/*
	 * Class:     dev_onvoid_webrtc_RTCEncodedFrame
	 * Method:    setDataBuffer
	 * Signature: (JLjava/nio/ByteBuffer;II)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedFrame_setDataBuffer
	(JNIEnv *, jclass, jlong, jobject, jint, jint);

	/*
	 * Class:     dev_onvoid_webrtc_RTCEncodedFrame
	 * Method:    setDataArray
	 * Signature: (J[BII)V
	 */
	JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedFrame_setDataArray
	(JNIEnv *, jclass, jlong, jbyteArray, jint, jint);

#ifdef __cplusplus
}
#endif
#endif
