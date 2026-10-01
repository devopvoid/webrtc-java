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

#include "media/video/codec/FallbackVideoDecoder.h"

#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

namespace jni
{
	FallbackVideoDecoder::FallbackVideoDecoder(std::unique_ptr<webrtc::VideoDecoder> hardware,
		std::unique_ptr<webrtc::VideoDecoder> software) :
		hardware(std::move(hardware)),
		software(std::move(software)),
		useSoftware(false),
		callback(nullptr)
	{
	}

	bool FallbackVideoDecoder::Configure(const Settings & decoderSettings)
	{
		settings = decoderSettings;

		if (!useSoftware) {
			if (hardware->Configure(decoderSettings)) {
				return true;
			}

			RTC_LOG(LS_WARNING) << "Hardware decoder failed to configure, switching to software";

			hardware->Release();
			useSoftware = true;
		}

		return StartSoftware();
	}

	bool FallbackVideoDecoder::StartSoftware()
	{
		if (!settings || !software->Configure(*settings)) {
			RTC_LOG(LS_WARNING) << "Software decoder failed to configure";
			return false;
		}

		if (callback != nullptr) {
			software->RegisterDecodeCompleteCallback(callback);
		}

		return true;
	}

	int32_t FallbackVideoDecoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		int32_t result = Active()->Decode(image, renderTimeMs);

		if (useSoftware || result != WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE) {
			return result;
		}

		RTC_LOG(LS_WARNING) << "Hardware decoder gave up, switching to software";

		hardware->Release();
		useSoftware = true;

		if (!StartSoftware()) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		return software->Decode(image, renderTimeMs);
	}

	int32_t FallbackVideoDecoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		hardware->RegisterDecodeCompleteCallback(decodeCallback);
		software->RegisterDecodeCompleteCallback(decodeCallback);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t FallbackVideoDecoder::Release()
	{
		return Active()->Release();
	}

	webrtc::VideoDecoder::DecoderInfo FallbackVideoDecoder::GetDecoderInfo() const
	{
		return Active()->GetDecoderInfo();
	}

	const char * FallbackVideoDecoder::ImplementationName() const
	{
		return Active()->ImplementationName();
	}

	webrtc::VideoDecoder * FallbackVideoDecoder::Active() const
	{
		return useSoftware ? software.get() : hardware.get();
	}
}
