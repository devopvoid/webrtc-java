#include "AudioCodecInfo.h"

#include "JavaObject.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni::AudioCodecInfo
{
    webrtc::AudioCodecInfo toNative(JNIEnv* env, const JavaRef<jobject>& javaType)
    {
        const auto javaClass = JavaClasses::get<JavaAudioCodecInfoClass>(env);

        JavaObject obj(env, javaType);

        jint sampleRateHz = obj.getInt(javaClass->sampleRateHz);
        jint numChannels = obj.getInt(javaClass->numChannels);
        jint defaultBitrateBps = obj.getInt(javaClass->defaultBitrateBps);
        jint minBitrateBps = obj.getInt(javaClass->minBitrateBps);
        jint maxBitrateBps = obj.getInt(javaClass->maxBitrateBps);
        jboolean allowComfortNoise = obj.getBoolean(javaClass->allowComfortNoise);
        jboolean supportsNetworkAdaption = obj.getBoolean(javaClass->supportsNetworkAdaption);

        webrtc::AudioCodecInfo info(sampleRateHz, numChannels, defaultBitrateBps, minBitrateBps, maxBitrateBps);
        info.allow_comfort_noise = allowComfortNoise;
        info.supports_network_adaption = supportsNetworkAdaption;

        return info;
    }

    JavaLocalRef<jobject> toJava(JNIEnv* env, const webrtc::AudioCodecInfo& info)
    {
        const auto javaClass = JavaClasses::get<JavaAudioCodecInfoClass>(env);

        jobject obj = env->NewObject(javaClass->cls, javaClass->ctor);
        env->SetIntField(obj, javaClass->sampleRateHz, info.sample_rate_hz);
        env->SetIntField(obj, javaClass->numChannels, static_cast<jint>(info.num_channels));
        env->SetIntField(obj, javaClass->defaultBitrateBps, info.default_bitrate_bps);
        env->SetIntField(obj, javaClass->minBitrateBps, info.min_bitrate_bps);
        env->SetIntField(obj, javaClass->maxBitrateBps, info.max_bitrate_bps);
        env->SetBooleanField(obj, javaClass->allowComfortNoise, info.allow_comfort_noise);
        env->SetBooleanField(obj, javaClass->supportsNetworkAdaption, info.supports_network_adaption);
        ExceptionCheck(env);
        return JavaLocalRef<jobject>(env, obj);
    }

    JavaAudioCodecInfoClass::JavaAudioCodecInfoClass(JNIEnv* env)
    {
        cls = FindClass(env, PKG_AUDIO"AudioCodecInfo");
        ctor = GetMethod(env, cls, "<init>", "()V");

        sampleRateHz = GetFieldID(env, cls, "sampleRateHz", "I");
        numChannels = GetFieldID(env, cls, "numChannels", "I");
        defaultBitrateBps = GetFieldID(env, cls, "defaultBitrateBps", "I");
        minBitrateBps = GetFieldID(env, cls, "minBitrateBps", "I");
        maxBitrateBps = GetFieldID(env, cls, "maxBitrateBps", "I");
        allowComfortNoise = GetFieldID(env, cls, "allowComfortNoise", "Z");
        supportsNetworkAdaption = GetFieldID(env, cls, "supportsNetworkAdaption", "Z");
    }
}
