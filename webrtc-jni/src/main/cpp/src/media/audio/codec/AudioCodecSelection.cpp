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

#include "media/audio/codec/AudioCodecSelection.h"

#include "absl/strings/match.h"

namespace jni
{
	bool IsSelectedAudioCodec(const std::string & name, const std::vector<std::string> & names)
	{
		for (const auto & selected : names) {
			if (absl::EqualsIgnoreCase(name, selected)) {
				return true;
			}
		}

		return false;
	}

	std::vector<webrtc::AudioCodecSpec> SelectAudioCodecs(const std::vector<webrtc::AudioCodecSpec> & specs,
		const std::vector<std::string> & names)
	{
		std::vector<webrtc::AudioCodecSpec> selected;

		for (const auto & name : names) {
			for (const auto & spec : specs) {
				if (absl::EqualsIgnoreCase(spec.format.name, name)) {
					selected.push_back(spec);
				}
			}
		}

		return selected;
	}
}
