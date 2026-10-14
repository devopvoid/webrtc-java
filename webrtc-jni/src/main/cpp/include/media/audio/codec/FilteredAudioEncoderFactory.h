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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_CODEC_FILTERED_AUDIO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_AUDIO_CODEC_FILTERED_AUDIO_ENCODER_FACTORY_H_

#include "api/audio_codecs/audio_encoder_factory.h"
#include "api/scoped_refptr.h"

#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace jni
{
	// Offers and creates only the codecs of another factory that have one of
	// the given names, in the order of the names.
	class FilteredAudioEncoderFactory : public webrtc::AudioEncoderFactory
	{
		public:
			FilteredAudioEncoderFactory(webrtc::scoped_refptr<webrtc::AudioEncoderFactory> factory,
				std::vector<std::string> names);

			std::vector<webrtc::AudioCodecSpec> GetSupportedEncoders() override;

			std::optional<webrtc::AudioCodecInfo> QueryAudioEncoder(const webrtc::SdpAudioFormat & format) override;

			std::unique_ptr<webrtc::AudioEncoder> Create(const webrtc::Environment & env,
				const webrtc::SdpAudioFormat & format, Options options) override;

		private:
			const webrtc::scoped_refptr<webrtc::AudioEncoderFactory> factory;
			const std::vector<std::string> names;
	};
}

#endif
