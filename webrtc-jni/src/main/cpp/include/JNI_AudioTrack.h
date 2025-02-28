/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class dev_onvoid_webrtc_media_audio_AudioTrack */

#ifndef _Included_dev_onvoid_webrtc_media_audio_AudioTrack
#define _Included_dev_onvoid_webrtc_media_audio_AudioTrack
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioTrack
 * Method:    getSignalLevel
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_getSignalLevel
  (JNIEnv *, jobject);

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioTrack
 * Method:    addSinkInternal
 * Signature: (Ldev/onvoid/webrtc/media/audio/AudioTrackSink;)J
 */
JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_addSinkInternal
  (JNIEnv *, jobject, jobject);

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioTrack
 * Method:    removeSinkInternal
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_removeSinkInternal
  (JNIEnv *, jobject, jlong);

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioTrack
 * Method:    getSource
 * Signature: ()Ldev/onvoid/webrtc/media/audio/AudioTrackSource;
 */
JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_getSource
  (JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif
