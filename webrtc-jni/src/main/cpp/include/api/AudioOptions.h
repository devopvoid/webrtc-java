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

#ifndef JNI_WEBRTC_API_AUDIO_OPTIONS_H_
#define JNI_WEBRTC_API_AUDIO_OPTIONS_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/audio_options.h"

#include <jni.h>

namespace jni
{
	namespace AudioOptions
	{
		class JavaAudioOptionsClass : public JavaClass
		{
			public:
				explicit JavaAudioOptionsClass(JNIEnv * env);

				jclass cls;
				jfieldID echoCancellation;
				jfieldID autoGainControl;
				jfieldID noiseSuppression;
				jfieldID highpassFilter;
				jfieldID typingDetection;
				jfieldID residualEchoDetector;
		};

		cricket::AudioOptions toNative(JNIEnv * env, const JavaRef<jobject> & javaType);
	}
}

#endif