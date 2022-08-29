//
// Created by kalgecin on 10/26/23.
//

#include "JNI_CustomVideoSource.h"
#include "media/video//CustomVideoSource.h"
#include "VideoFrame.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_CustomVideoSource_OnFrameCaptured
        (JNIEnv *env, jobject obj, jobject videoFrame) {
    jclass c = env->GetObjectClass(obj);
    jfieldID fid_handle = GetHandleField(env, obj, "nativeHandle");
    auto * nativeObject = (jni::CustomVideoSource *) env->GetLongField(obj, fid_handle);

    const webrtc::VideoFrame &nativeFrame = jni::VideoFrame::toNative(env, jni::JavaLocalRef<jobject>(env, videoFrame));
    nativeObject->OnFrameCaptured(nativeFrame);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_CustomVideoSource_initialize
        (JNIEnv * env, jobject caller)
{
    rtc::scoped_refptr<jni::CustomVideoSource> videoSource = rtc::scoped_refptr<jni::CustomVideoSource>(new rtc::RefCountedObject<jni::CustomVideoSource>());

    SetHandle(env, caller, videoSource.release());
}
