#include <api/transport/bitrate_settings.h>

#include "BitrateSettings.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
    namespace BitrateSettings
    {
        webrtc::BitrateSettings toNative(JNIEnv * env, const JavaRef<jobject>& javaType)
        {
            const auto javaClass = JavaClasses::get<JavaBitrateSettingsClass>(env);
            JavaObject obj(env, javaType);

            auto settings = webrtc::BitrateSettings();
            settings.start_bitrate_bps = obj.getInt(javaClass->startBitrateBps);
            settings.min_bitrate_bps = obj.getInt(javaClass->minBitrateBps);
            settings.max_bitrate_bps = obj.getInt(javaClass->maxBitrateBps);

            return settings;
        }

        JavaBitrateSettingsClass::JavaBitrateSettingsClass(JNIEnv * env)
        {
            cls = FindClass(env, PKG"BitrateSettings");

            minBitrateBps = GetFieldID(env, cls, "minBitrateBps", "I");
            startBitrateBps = GetFieldID(env, cls, "startBitrateBps", "I");
            maxBitrateBps = GetFieldID(env, cls, "maxBitrateBps", "I");
        }
    }
}
