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

#include "media/audio/codec/FilteredAudioDecoderFactory.h"
#include "media/audio/codec/AudioCodecSelection.h"

#include <utility>

namespace jni
{
	FilteredAudioDecoderFactory::FilteredAudioDecoderFactory(webrtc::scoped_refptr<webrtc::AudioDecoderFactory> factory,
		std::vector<std::string> names) :
		factory(std::move(factory)),
		names(std::move(names))
	{
	}

	std::vector<webrtc::AudioCodecSpec> FilteredAudioDecoderFactory::GetSupportedDecoders()
	{
		return SelectAudioCodecs(factory->GetSupportedDecoders(), names);
	}

	bool FilteredAudioDecoderFactory::IsSupportedDecoder(const webrtc::SdpAudioFormat & format)
	{
		return IsSelectedAudioCodec(format.name, names) && factory->IsSupportedDecoder(format);
	}

	std::unique_ptr<webrtc::AudioDecoder> FilteredAudioDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpAudioFormat & format)
	{
		if (!IsSelectedAudioCodec(format.name, names)) {
			return nullptr;
		}

		return factory->Create(env, format);
	}
}
