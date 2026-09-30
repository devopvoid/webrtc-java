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

#include "media/video/codec/FallbackVideoEncoder.h"

#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

namespace jni
{
	FallbackVideoEncoder::FallbackVideoEncoder(std::unique_ptr<webrtc::VideoEncoder> hardware,
		std::unique_ptr<webrtc::VideoEncoder> software) :
		hardware(std::move(hardware)),
		software(std::move(software)),
		useSoftware(false),
		initialized(false),
		callback(nullptr)
	{
	}

	int32_t FallbackVideoEncoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		codecSettings = *settings;
		this->encoderSettings = encoderSettings;
		rates.reset();

		if (!useSoftware) {
			int32_t result = hardware->InitEncode(settings, encoderSettings);

			if (result == WEBRTC_VIDEO_CODEC_OK) {
				initialized = true;
				return result;
			}
			if (result == WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED) {
				// WebRTC then encodes each stream with an encoder of its own.
				return result;
			}

			RTC_LOG(LS_WARNING) << "Hardware encoder failed to initialize (" << result
				<< "), switching to software";

			hardware->Release();
			useSoftware = true;
		}

		initialized = StartSoftware();

		return initialized ? WEBRTC_VIDEO_CODEC_OK : WEBRTC_VIDEO_CODEC_ERROR;
	}

	bool FallbackVideoEncoder::StartSoftware()
	{
		if (!codecSettings || !encoderSettings) {
			return false;
		}

		if (software->InitEncode(&*codecSettings, *encoderSettings) != WEBRTC_VIDEO_CODEC_OK) {
			RTC_LOG(LS_WARNING) << "Software encoder failed to initialize";
			return false;
		}

		if (callback != nullptr) {
			software->RegisterEncodeCompleteCallback(callback);
		}
		if (rates) {
			software->SetRates(*rates);
		}

		return true;
	}

	int32_t FallbackVideoEncoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		hardware->RegisterEncodeCompleteCallback(encodeCallback);
		software->RegisterEncodeCompleteCallback(encodeCallback);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t FallbackVideoEncoder::Release()
	{
		initialized = false;

		return Active()->Release();
	}

	int32_t FallbackVideoEncoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (!initialized) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		int32_t result = Active()->Encode(frame, frameTypes);

		if (useSoftware || result != WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE) {
			return result;
		}

		RTC_LOG(LS_WARNING) << "Hardware encoder gave up, switching to software";

		hardware->Release();
		useSoftware = true;
		initialized = StartSoftware();

		if (!initialized) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		// The receiver has to start over with what the software encoder
		// produces, so that begins with a key frame.
		std::vector<webrtc::VideoFrameType> keyFrames(frameTypes != nullptr ? frameTypes->size() : 1,
			webrtc::VideoFrameType::kVideoFrameKey);

		return software->Encode(frame, &keyFrames);
	}

	void FallbackVideoEncoder::SetRates(const RateControlParameters & parameters)
	{
		rates = parameters;

		Active()->SetRates(parameters);
	}

	void FallbackVideoEncoder::OnPacketLossRateUpdate(float packetLossRate)
	{
		Active()->OnPacketLossRateUpdate(packetLossRate);
	}

	void FallbackVideoEncoder::OnRttUpdate(int64_t rttMs)
	{
		Active()->OnRttUpdate(rttMs);
	}

	webrtc::VideoEncoder::EncoderInfo FallbackVideoEncoder::GetEncoderInfo() const
	{
		return Active()->GetEncoderInfo();
	}

	webrtc::VideoEncoder * FallbackVideoEncoder::Active() const
	{
		return useSoftware ? software.get() : hardware.get();
	}
}
