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

#include "media/video/codec/windows/MFVideoDecoderFactory.h"
#include "media/video/codec/windows/MFDecoderUtils.h"
#include "media/video/codec/windows/MFVideoDecoder.h"
#include "platform/windows/ComInitializer.h"
#include "platform/windows/MFInitializer.h"

#include "api/video_codecs/video_codec.h"
#include "modules/video_coding/codecs/h264/include/h264.h"
#include "rtc_base/logging.h"

using Microsoft::WRL::ComPtr;

namespace jni
{
	namespace
	{
		// Whether Windows has a decoder for the format that decodes on a
		// Direct3D 11 device.
		bool HasDirect3DDecoder(const GUID & format, IMFDXGIDeviceManager * manager)
		{
			std::vector<ComPtr<IMFActivate>> decoders;

			if (FAILED(EnumerateDecoders(format, decoders)) || decoders.empty()) {
				return false;
			}

			ComPtr<IMFActivate> activate;
			ComPtr<IMFTransform> transform;

			if (FAILED(ActivateDirect3DDecoder(decoders, manager, activate, transform))) {
				return false;
			}

			transform.Reset();
			activate->ShutdownObject();

			return true;
		}
	}

	std::unique_ptr<MFVideoDecoderFactory> MFVideoDecoderFactory::Create()
	{
		bool h264 = false;
		bool av1 = false;

		try {
			ComInitializer comInitializer;
			MFInitializer mfInitializer;

			ComPtr<ID3D11Device> device;
			ComPtr<ID3D11DeviceContext> context;

			if (FAILED(CreateVideoDevice(device, context))) {
				RTC_LOG(LS_INFO) << "Media Foundation decoders: no Direct3D 11 video device";
				return nullptr;
			}

			ComPtr<IMFDXGIDeviceManager> manager;
			UINT resetToken = 0;

			if (FAILED(MFCreateDXGIDeviceManager(&resetToken, &manager)) ||
				FAILED(manager->ResetDevice(device.Get(), resetToken)))
			{
				return nullptr;
			}

			// The GPU has to decode the codec, and Windows has to have a
			// decoder that lets it.
			h264 = SupportsDecoderProfile(device.Get(), D3D11_DECODER_PROFILE_H264_VLD_NOFGT)
				&& HasDirect3DDecoder(MFVideoFormat_H264, manager.Get());
			av1 = SupportsDecoderProfile(device.Get(), D3D11_DECODER_PROFILE_AV1_VLD_PROFILE0)
				&& HasDirect3DDecoder(MFVideoFormat_AV1, manager.Get());
		}
		catch (...) {
			return nullptr;
		}

		RTC_LOG(LS_INFO) << "Media Foundation hardware decoders, H.264: " << h264 << ", AV1: " << av1;

		if (!h264 && !av1) {
			return nullptr;
		}

		return std::unique_ptr<MFVideoDecoderFactory>(new MFVideoDecoderFactory(h264, av1));
	}

	MFVideoDecoderFactory::MFVideoDecoderFactory(bool h264, bool av1) :
		h264(h264),
		av1(av1)
	{
	}

	std::vector<webrtc::SdpVideoFormat> MFVideoDecoderFactory::GetSupportedFormats() const
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		if (h264) {
			formats = webrtc::SupportedH264DecoderCodecs();
		}
		if (av1) {
			formats.push_back(webrtc::SdpVideoFormat::AV1Profile0());
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoDecoder> MFVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<MFVideoDecoder>(webrtc::PayloadStringToCodecType(format.name));
	}
}
