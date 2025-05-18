#include "JNI_AudioDecoderFactory.h"

#include <AudioCodecSpec.h>
#include <RTCRtpCodecParameters.h>
#include <SdpAudioFormat.h>
#include <api/audio_codecs/audio_decoder_factory.h>
#include <api/audio_codecs/builtin_audio_decoder_factory.h>
#include <common_video/libyuv/include/webrtc_libyuv.h>

#include "JavaArrayList.h"
#include "JavaFactories.h"
#include "JavaUtils.h"
/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioDecoderFactory
 * Method:    getSupportedDecoders
 * Signature: ()Ljava/util/List;
 */
JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDecoderFactory_getSupportedDecoders
  (JNIEnv * env, jobject caller)
{
    webrtc::AudioDecoderFactory * factory = GetHandle<webrtc::AudioDecoderFactory>(env, caller);
    CHECK_HANDLEV(factory, nullptr);

    try
    {
        auto decoders = factory->GetSupportedDecoders();
        jni::JavaArrayList decoderList(env,decoders.size());

        for (const auto & decoder : decoders)
        {
            decoderList.add(jni::AudioCodecSpec::toJava(env, decoder));
        }
        return decoderList.listObject().release();
    }catch (...)
    {
        ThrowCxxJavaException(env);
    }

    return nullptr;
}

/*
 * Class:     dev_onvoid_webrtc_media_audio_AudioDecoderFactory
 * Method:    isSupportedDecoder
 * Signature: (Ldev/onvoid/webrtc/media/audio/SdpAudioFormat;)Z
 */
JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_audio_AudioDecoderFactory_isSupportedDecoder
  (JNIEnv * env, jobject caller, jobject jformat)
{
    webrtc::AudioDecoderFactory * factory = GetHandle<webrtc::AudioDecoderFactory>(env, caller);
    CHECK_HANDLEV(factory, false);

    webrtc::SdpAudioFormat format = jni::SdpAudioFormat::toNative(env, jni::JavaLocalRef<jobject>(env, jformat));

    auto result = factory->IsSupportedDecoder(format);
    return result;
}
