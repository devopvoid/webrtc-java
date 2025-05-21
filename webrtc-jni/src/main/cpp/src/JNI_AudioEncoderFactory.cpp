#include "JNI_AudioEncoderFactory.h"

#include <AudioCodecInfo.h>
#include <AudioCodecSpec.h>
#include <SdpAudioFormat.h>
#include <api/audio_codecs/audio_encoder_factory.h>
#include <api/audio_codecs/builtin_audio_encoder_factory.h>
#include <common_video/libyuv/include/webrtc_libyuv.h>

#include "JavaArrayList.h"
#include "JavaFactories.h"
#include "JavaList.h"

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioEncoderFactory
 * Method:    getSupportedEncoders
 * Signature: ()Ljava/util/List;
 */
JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_AudioEncoderFactory_getSupportedEncoders
(JNIEnv* env, jobject caller)
{
    webrtc::AudioEncoderFactory* factory = GetHandle<webrtc::AudioEncoderFactory>(env, caller);
    CHECK_HANDLEV(factory, nullptr);
    try
    {
        auto encoders = factory->GetSupportedEncoders();
        jni::JavaArrayList encoderList(env, encoders.size());

        for (const auto& encoder : encoders)
        {
            encoderList.add(jni::AudioCodecSpec::toJava(env, encoder));
        }
        return encoderList.listObject().release();
    }
    catch (...)
    {
        ThrowCxxJavaException(env);
    }

    return nullptr;
}

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioEncoderFactory
 * Method:    queryAudioEncoder
 * Signature: (Ldev/onvoid/webrtc/media/audio/SdpAudioFormat;)Ldev/onvoid/webrtc/media/audio/AudioCodecInfo;
 */
JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_AudioEncoderFactory_queryAudioEncoder
(JNIEnv* env, jobject caller, jobject jformat)
{
    webrtc::AudioEncoderFactory* factory = GetHandle<webrtc::AudioEncoderFactory>(env, caller);
    CHECK_HANDLEV(factory, nullptr);

    webrtc::SdpAudioFormat format = jni::SdpAudioFormat::toNative(env, jni::JavaLocalRef<jobject>(env, jformat));

    auto result = factory->QueryAudioEncoder(format);

    if (!result.has_value())
    {
        return nullptr;
    }

    return jni::AudioCodecInfo::toJava(env, *result).release();
}
