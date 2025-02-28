/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef JNI_WEBRTC_API_BITRATE_SETTINGS_H_
#define JNI_WEBRTC_API_BITRATE_SETTINGS_H_

#include "JavaClass.h"
#include "JavaRef.h"
#include "api/transport/bitrate_settings.h"
#include <jni.h>

namespace jni
{
	namespace BitrateSettings
	{
		class JavaBitrateSettingsClass : public JavaClass
		{
			public:
				explicit JavaBitrateSettingsClass(JNIEnv * env);

				jclass cls;
				jfieldID minBitrateBps;
				jfieldID startBitrateBps;
				jfieldID maxBitrateBps;
		};

		webrtc::BitrateSettings toNative(JNIEnv * env, const JavaRef<jobject> & javaType);
	}
}

#endif