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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_CODEC_AUDIO_CODEC_SELECTION_H_
#define JNI_WEBRTC_MEDIA_AUDIO_CODEC_AUDIO_CODEC_SELECTION_H_

#include "api/audio_codecs/audio_format.h"

#include <string>
#include <vector>

namespace jni
{
	// Whether a codec name is one of the selected names, ignoring case as SDP
	// does.
	bool IsSelectedAudioCodec(const std::string & name, const std::vector<std::string> & names);

	// Returns the codecs with the selected names, ordered by the names and,
	// for one name, by their order in `specs`. The Java side,
	// AudioCodecSelection.select, orders the same way.
	std::vector<webrtc::AudioCodecSpec> SelectAudioCodecs(const std::vector<webrtc::AudioCodecSpec> & specs,
		const std::vector<std::string> & names);
}

#endif
