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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_CODEC_FILTERED_AUDIO_DECODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_AUDIO_CODEC_FILTERED_AUDIO_DECODER_FACTORY_H_

#include "api/audio_codecs/audio_decoder_factory.h"
#include "api/scoped_refptr.h"

#include <memory>
#include <string>
#include <vector>

namespace jni
{
	// Offers and creates only the decoders of another factory that have one
	// of the given names, in the order of the names.
	class FilteredAudioDecoderFactory : public webrtc::AudioDecoderFactory
	{
		public:
			FilteredAudioDecoderFactory(webrtc::scoped_refptr<webrtc::AudioDecoderFactory> factory,
				std::vector<std::string> names);

			std::vector<webrtc::AudioCodecSpec> GetSupportedDecoders() override;

			bool IsSupportedDecoder(const webrtc::SdpAudioFormat & format) override;

			// The variant with a codec pair ID calls this one.
			using webrtc::AudioDecoderFactory::Create;

			std::unique_ptr<webrtc::AudioDecoder> Create(const webrtc::Environment & env,
				const webrtc::SdpAudioFormat & format) override;

		private:
			const webrtc::scoped_refptr<webrtc::AudioDecoderFactory> factory;
			const std::vector<std::string> names;
	};
}

#endif
