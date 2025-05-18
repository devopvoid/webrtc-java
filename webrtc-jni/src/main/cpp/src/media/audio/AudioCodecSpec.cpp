#include "AudioCodecSpec.h"

#include <AudioCodecInfo.h>
#include <SdpAudioFormat.h>
#include "JavaObject.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
    namespace AudioCodecSpec
    {
        webrtc::AudioCodecSpec toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
        {
            const auto javaClass = JavaClasses::get<JavaAudioCodecSpecClass>(env);

            JavaObject obj(env, javaType);

            JavaLocalRef<jobject> format = obj.getObject(javaClass->format);
            JavaLocalRef<jobject> info = obj.getObject(javaClass->info);
             
            webrtc::AudioCodecSpec spec{
                SdpAudioFormat::toNative(env, format),
                AudioCodecInfo::toNative(env, info)
            };

            return spec;
        }
        JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::AudioCodecSpec & spec)
        {
            const auto javaClass = JavaClasses::get<JavaAudioCodecSpecClass>(env);

            JavaLocalRef<jobject> format = SdpAudioFormat::toJava(env, spec.format);
            JavaLocalRef<jobject> info = AudioCodecInfo::toJava(env, spec.info);

            jobject obj = env->NewObject(javaClass->cls, javaClass->ctor);
            env->SetObjectField(obj, javaClass->format, format.get());
            env->SetObjectField(obj, javaClass->info, info.get());
            ExceptionCheck(env);

            return JavaLocalRef<jobject>(env, obj);
        }
        JavaAudioCodecSpecClass::JavaAudioCodecSpecClass(JNIEnv * env)
        {
            cls = FindClass(env, PKG_AUDIO"AudioCodecSpec");

            ctor = GetMethod(env, cls, "<init>", "()V");
            
            info = GetFieldID(env, cls, "info", "L" PKG_AUDIO "AudioCodecInfo;");
            format = GetFieldID(env, cls, "format", "L" PKG_AUDIO "SdpAudioFormat;"); 
        }
    }
}
