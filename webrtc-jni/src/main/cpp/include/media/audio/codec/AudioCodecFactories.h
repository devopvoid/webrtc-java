/*
 * Copyright 2026 Alex Andres
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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_CODEC_AUDIO_CODEC_FACTORIES_H_
#define JNI_WEBRTC_MEDIA_AUDIO_CODEC_AUDIO_CODEC_FACTORIES_H_

#include "JavaRef.h"

#include "api/audio_codecs/audio_decoder_factory.h"
#include "api/audio_codecs/audio_encoder_factory.h"
#include "api/scoped_refptr.h"

#include <jni.h>

namespace jni
{
	// Creates the native factory for a Java BuiltinAudioEncoderFactory: the
	// built-in factory, limited to the codecs the Java object selected. A
	// null object stands for all built-in codecs.
	webrtc::scoped_refptr<webrtc::AudioEncoderFactory> CreateAudioEncoderFactory(JNIEnv * env,
		const JavaRef<jobject> & factory);

	// The same for a Java BuiltinAudioDecoderFactory.
	webrtc::scoped_refptr<webrtc::AudioDecoderFactory> CreateAudioDecoderFactory(JNIEnv * env,
		const JavaRef<jobject> & factory);
}

#endif
