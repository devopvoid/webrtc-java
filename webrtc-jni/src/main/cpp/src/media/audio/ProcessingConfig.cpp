#include "media/audio/ProcessingConfig.h"

#include <audio/AudioProcessingStreamConfig.h>

#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaHashMap.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
    namespace ProcessingConfig
    {
        
        webrtc::ProcessingConfig toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
        {
            const auto javaClass = JavaClasses::get<JavaProcessingConfigClass>(env);
            JavaObject obj(env, javaType);

            webrtc::ProcessingConfig config;

            JavaLocalRef<jobject> streams = obj.getObject(javaClass->streams);
            for (const auto & entry : JavaHashMap(env, streams))
            {
                webrtc::ProcessingConfig::StreamName key = JavaEnums::toNative<webrtc::ProcessingConfig::StreamName>(env, entry.first);
                webrtc::StreamConfig value = AudioProcessingStreamConfig::toNative(env, entry.second);
                config.streams[key] = value;
            }

            return config;
        }
        JavaLocalRef<jobject> toJava(JNIEnv * env, webrtc::ProcessingConfig config)
        {
            JavaHashMap streams(env);

            for (int i = 0; i < webrtc::ProcessingConfig::StreamName::kNumStreamNames; ++i)
            {
                JavaLocalRef<jobject> key = JavaEnums::toJava(env, static_cast<webrtc::ProcessingConfig::StreamName>(i));
                JavaLocalRef<jobject> value = AudioProcessingStreamConfig::toJava(env, config.streams[i]);

                streams.put(key, value);
            }

            const auto javaClass = JavaClasses::get<JavaProcessingConfigClass>(env);
            jobject obj = env->NewObject(javaClass->cls, javaClass->ctor, ((JavaLocalRef<jobject>) streams).get());
            ExceptionCheck(env);

            return JavaLocalRef<jobject>(env, obj);
        }

        JavaProcessingConfigClass::JavaProcessingConfigClass(JNIEnv * env)
        {
            cls = FindClass(env, PKG_AUDIO"ProcessingConfig");
            ctor = GetMethod(env, cls, "<init>", "(" MAP_SIG ")V");
            streams = GetFieldID(env, cls, "streams", MAP_SIG);
        }
    }
}
