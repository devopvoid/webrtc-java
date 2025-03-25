#include "SdpAudioFormat.h"

#include <JNI_WebRTC.h>

#include "JavaHashMap.h"
#include "JavaObject.h"
#include "JavaString.h"

namespace jni
{
    namespace SdpAudioFormat
    {
        webrtc::SdpAudioFormat toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
        {
            const auto javaClass = JavaClasses::get<JavaSdpAudioFormatClass>(env);

            JavaObject obj(env, javaType);

            JavaLocalRef<jstring> name = obj.getString(javaClass->name);
            jint clockRateHz = obj.getInt(javaClass->clockRateHz);
            jint numChannels = obj.getInt(javaClass->numChannels);
            JavaLocalRef<jobject> parameters = obj.getObject(javaClass->parameters);

            auto native_name = JavaString::toNative(env, name);

            std::map<std::string, std::string> params;

            for (const auto & entry : JavaHashMap(env, parameters))
            {
                std::string key = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.first));
                std::string value = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.second));
                params.emplace(key, value);
            }
            
            webrtc::SdpAudioFormat format(native_name, clockRateHz, numChannels, params);
            return format;
        }
        JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SdpAudioFormat & info)
        {
            const auto javaClass = JavaClasses::get<JavaSdpAudioFormatClass>(env);

            jobject obj = env->NewObject(javaClass->cls, javaClass->ctor);

            JavaHashMap params(env);

            for (auto & parameter : info.parameters)
            {
                JavaLocalRef<jstring> key = JavaString::toJava(env, parameter.first);
                JavaLocalRef<jstring> value = JavaString::toJava(env, parameter.second);

                params.put(key, value);
            }

            env->SetObjectField(obj, javaClass->parameters, ((JavaLocalRef<jobject>) params).get());
            env->SetObjectField(obj, javaClass->name, JavaString::toJava(env, info.name).get());
            env->SetIntField(obj, javaClass->clockRateHz, info.clockrate_hz);
            env->SetIntField(obj, javaClass->numChannels, static_cast<jint>(info.num_channels));
            ExceptionCheck(env);
            return JavaLocalRef<jobject>(env, obj);
        }

        JavaSdpAudioFormatClass::JavaSdpAudioFormatClass(JNIEnv* env)
        {
            cls = FindClass(env, PKG_AUDIO"SdpAudioFormat");
            ctor = GetMethod(env, cls, "<init>", "()V");

            name = GetFieldID(env, cls, "name", STRING_SIG);
            clockRateHz = GetFieldID(env, cls, "clockRateHz", "I");
            numChannels = GetFieldID(env, cls, "numChannels", "I");
            parameters = GetFieldID(env, cls, "parameters", MAP_SIG);
        }
    }
}
