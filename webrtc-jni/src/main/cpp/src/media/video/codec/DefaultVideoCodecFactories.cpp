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

#include "media/video/codec/DefaultVideoCodecFactories.h"

#include "media/video/codec/HardwareCodecOptions.h"
#include "media/video/codec/HardwareVideoDecoderFactory.h"
#include "media/video/codec/HardwareVideoEncoderFactory.h"

#include "api/video/video_codec_type.h"
#include "api/video_codecs/video_codec.h"

#include <map>
#include <mutex>
#include <optional>
#include <utility>

#ifdef __APPLE__
#include "sdk/objc/components/video_codec/RTCDefaultVideoDecoderFactory.h"
#include "sdk/objc/components/video_codec/RTCDefaultVideoEncoderFactory.h"
#include "sdk/objc/native/api/video_decoder_factory.h"
#include "sdk/objc/native/api/video_encoder_factory.h"
#else
#include "api/video_codecs/video_decoder_factory_template.h"
#include "api/video_codecs/video_decoder_factory_template_dav1d_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp9_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_open_h264_adapter.h"
#include "api/video_codecs/video_encoder_factory_template.h"
#include "api/video_codecs/video_encoder_factory_template_libaom_av1_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp9_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_open_h264_adapter.h"
#endif

namespace jni
{
	namespace
	{
		// WebRTC's Objective-C factories encode and decode H.264 with
		// VideoToolbox, but do not report it as power efficient.
		bool IsDefaultHardwareFormat(const webrtc::SdpVideoFormat & format)
		{
#ifdef __APPLE__
			return webrtc::PayloadStringToCodecType(format.name) == webrtc::kVideoCodecH264;
#else
			return false;
#endif
		}

		// Probes for the hardware factories of the platform once for each
		// setting of the option that changes what is found, and shares what
		// it found: a probe takes tens of milliseconds, and so every factory
		// works with the same codecs. A GPU, driver or codec installed or
		// removed later is noticed only by a new process.
		template <typename Factories>
		std::shared_ptr<const Factories> GetPlatformHardwareFactories(Factories (*probe)())
		{
			static std::mutex mutex;
			// Never destroyed: the factories use libraries that may be gone
			// by the time static objects are destroyed as the process exits.
			static auto * cache = new std::map<bool, std::shared_ptr<const Factories>>();

			const bool vp9Encoder = IsHardwareVp9EncoderEnabled();

			std::lock_guard<std::mutex> lock(mutex);

			std::shared_ptr<const Factories> & factories = (*cache)[vp9Encoder];

			if (!factories) {
				factories = std::make_shared<const Factories>(probe());
			}

			return factories;
		}
	}

	std::unique_ptr<webrtc::VideoEncoderFactory> CreateDefaultVideoEncoderFactory()
	{
#ifdef __APPLE__
		return webrtc::ObjCToNativeVideoEncoderFactory([[RTC_OBJC_TYPE(RTCDefaultVideoEncoderFactory) alloc] init]);
#else
		return std::make_unique<webrtc::VideoEncoderFactoryTemplate<
			webrtc::LibvpxVp8EncoderTemplateAdapter,
			webrtc::LibvpxVp9EncoderTemplateAdapter,
			webrtc::OpenH264EncoderTemplateAdapter,
			webrtc::LibaomAv1EncoderTemplateAdapter>>();
#endif
	}

	std::unique_ptr<webrtc::VideoEncoderFactory> CreateHardwareVideoEncoderFactory()
	{
		auto hardware = GetPlatformHardwareFactories(&CreatePlatformHardwareVideoEncoderFactories);

		if (hardware->empty()) {
			return CreateDefaultVideoEncoderFactory();
		}

		return std::make_unique<HardwareVideoEncoderFactory>(std::move(hardware), CreateDefaultVideoEncoderFactory());
	}

	std::unique_ptr<webrtc::VideoDecoderFactory> CreateDefaultVideoDecoderFactory()
	{
#ifdef __APPLE__
		return webrtc::ObjCToNativeVideoDecoderFactory([[RTC_OBJC_TYPE(RTCDefaultVideoDecoderFactory) alloc] init]);
#else
		return std::make_unique<webrtc::VideoDecoderFactoryTemplate<
			webrtc::LibvpxVp8DecoderTemplateAdapter,
			webrtc::LibvpxVp9DecoderTemplateAdapter,
			webrtc::OpenH264DecoderTemplateAdapter,
			webrtc::Dav1dDecoderTemplateAdapter>>();
#endif
	}

	std::unique_ptr<webrtc::VideoDecoderFactory> CreateHardwareVideoDecoderFactory()
	{
		auto hardware = GetPlatformHardwareFactories(&CreatePlatformHardwareVideoDecoderFactories);

		if (hardware->empty()) {
			return CreateDefaultVideoDecoderFactory();
		}

		return std::make_unique<HardwareVideoDecoderFactory>(std::move(hardware), CreateDefaultVideoDecoderFactory());
	}

	std::vector<webrtc::SdpVideoFormat> GetHardwareVideoEncoderFormats(const webrtc::VideoEncoderFactory & factory)
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		for (const webrtc::SdpVideoFormat & format : factory.GetSupportedFormats()) {
			if (IsDefaultHardwareFormat(format)
				|| factory.QueryCodecSupport(format, std::nullopt).is_power_efficient) {
				formats.push_back(format);
			}
		}

		return formats;
	}

	std::vector<webrtc::SdpVideoFormat> GetHardwareVideoDecoderFormats(const webrtc::VideoDecoderFactory & factory)
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		for (const webrtc::SdpVideoFormat & format : factory.GetSupportedFormats()) {
			if (IsDefaultHardwareFormat(format)
				|| factory.QueryCodecSupport(format, false).is_power_efficient) {
				formats.push_back(format);
			}
		}

		return formats;
	}
}
