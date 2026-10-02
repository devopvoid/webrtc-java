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

#include "media/video/codec/HardwareVideoDecoderFactory.h"
#include "media/video/codec/HardwareVideoEncoderFactory.h"

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
		std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> hardware = CreatePlatformHardwareVideoEncoderFactories();

		if (hardware.empty()) {
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
		std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> hardware = CreatePlatformHardwareVideoDecoderFactories();

		if (hardware.empty()) {
			return CreateDefaultVideoDecoderFactory();
		}

		return std::make_unique<HardwareVideoDecoderFactory>(std::move(hardware), CreateDefaultVideoDecoderFactory());
	}
}
