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

#include "media/audio/codec/FilteredAudioEncoderFactory.h"
#include "media/audio/codec/AudioCodecSelection.h"

#include <utility>

namespace jni
{
	FilteredAudioEncoderFactory::FilteredAudioEncoderFactory(webrtc::scoped_refptr<webrtc::AudioEncoderFactory> factory,
		std::vector<std::string> names) :
		factory(std::move(factory)),
		names(std::move(names))
	{
	}

	std::vector<webrtc::AudioCodecSpec> FilteredAudioEncoderFactory::GetSupportedEncoders()
	{
		return SelectAudioCodecs(factory->GetSupportedEncoders(), names);
	}

	std::optional<webrtc::AudioCodecInfo> FilteredAudioEncoderFactory::QueryAudioEncoder(const webrtc::SdpAudioFormat & format)
	{
		if (!IsSelectedAudioCodec(format.name, names)) {
			return std::nullopt;
		}

		return factory->QueryAudioEncoder(format);
	}

	std::unique_ptr<webrtc::AudioEncoder> FilteredAudioEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpAudioFormat & format, Options options)
	{
		if (!IsSelectedAudioCodec(format.name, names)) {
			return nullptr;
		}

		return factory->Create(env, format, std::move(options));
	}
}
