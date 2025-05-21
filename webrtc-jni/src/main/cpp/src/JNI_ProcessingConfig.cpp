#include "JNI_ProcessingConfig.h"

#include <audio/AudioProcessingStreamConfig.h>
#include <common_video/libyuv/include/webrtc_libyuv.h>
#include <modules/audio_processing/include/audio_processing.h>

#include "JavaEnums.h"
#include "JavaUtils.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_ProcessingConfig_setProcessingConfig
(JNIEnv* env, jobject caller, jobject jstreamName, jobject jStreamConfig)
{
    webrtc::ProcessingConfig* pc = GetHandle<webrtc::ProcessingConfig>(env, caller);
    CHECK_HANDLE(pc);

    auto stream_name = jni::JavaEnums::toNative<webrtc::ProcessingConfig::StreamName>(env, jstreamName);
    auto config = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, jStreamConfig));

    pc->streams[stream_name] = config;
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_ProcessingConfig_getProcessingConfig
(JNIEnv* env, jobject caller, jobject jstreamName)
{
    webrtc::ProcessingConfig* pc = GetHandle<webrtc::ProcessingConfig>(env, caller);
    CHECK_HANDLEV(pc, nullptr);

    auto stream_name = jni::JavaEnums::toNative<webrtc::ProcessingConfig::StreamName>(env, jstreamName);

    return jni::AudioProcessingStreamConfig::toJava(env, pc->streams[stream_name]);
}
