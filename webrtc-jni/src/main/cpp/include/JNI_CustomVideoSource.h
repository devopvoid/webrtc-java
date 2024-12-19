//
// Created by kalgecin on 10/26/23.
//
#include <jni.h>

#ifndef WEBRTC_JAVA_JNI_CUSTOMVIDEOSOURCE_H
#define WEBRTC_JAVA_JNI_CUSTOMVIDEOSOURCE_H
#ifdef __cplusplus
extern "C" {
#endif
    JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_CustomVideoSource_initialize
            (JNIEnv * env, jobject caller);
    JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_CustomVideoSource_OnFrameCaptured
            (JNIEnv *, jobject, jobject);
#ifdef __cplusplus
}
#endif
#endif //WEBRTC_JAVA_JNI_CUSTOMVIDEOSOURCE_H
