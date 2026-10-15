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

#include "media/video/codec/linux/VaapiVp9Encoder.h"

#include "api/video/encoded_image.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// Key frames come when WebRTC asks for them; this only bounds how
		// long a stream may go without one if it never does.
		constexpr uint32_t kKeyFrameInterval = 3000;

		// Surfaces are kept to whole superblocks, which the driver may write
		// all of, as FFmpeg's VA-API encoder does.
		constexpr uint32_t kSurfaceAlignment = 64;

		// VP9 frame types (7.2 of the bitstream specification).
		constexpr uint32_t kKeyFrameType = 0;
		constexpr uint32_t kInterFrameType = 1;

		// The quantizer index and loop filter settings the driver starts
		// from, FFmpeg's defaults; the rate control sets the quantizer of each
		// frame.
		constexpr uint8_t kQIndex = 100;
		constexpr uint8_t kFilterLevel = 16;
		constexpr uint8_t kSharpnessLevel = 4;

		// The reference slot the next frame refers to as its last frame.
		constexpr uint32_t kLastSlot = 0;

		uint32_t Align(uint32_t value, uint32_t alignment)
		{
			return (value + alignment - 1) / alignment * alignment;
		}
	}

	VaapiVp9Encoder::VaapiVp9Encoder(VaapiLibrary & library, VAEntrypoint entrypoint,
		const webrtc::SdpVideoFormat & format) :
		library(library),
		entrypoint(entrypoint),
		implementationName("VA-API (" + library.Vendor() + ")"),
		session(library, "VA-API VP9"),
		initialized(false),
		codecSettings(),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		current(0),
		referenceValid(false),
		callback(nullptr),
		outputProcessor(webrtc::kVideoCodecVP9, format)
	{
	}

	VaapiVp9Encoder::~VaapiVp9Encoder()
	{
		Release();
	}

	int32_t VaapiVp9Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// One spatial and one temporal layer is all this encoder produces.
		if (settings->VP9().numberOfSpatialLayers > 1 || settings->VP9().numberOfTemporalLayers > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		codecSettings = *settings;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		current = 0;
		referenceValid = false;
		outputProcessor.Reset();

		const uint32_t surfaceWidth = Align(codecSettings.width, kSurfaceAlignment);
		const uint32_t surfaceHeight = Align(codecSettings.height, kSurfaceAlignment);

		if (!session.Create(VAProfileVP9Profile0, entrypoint, surfaceWidth, surfaceHeight, surfaceWidth, surfaceHeight))
		{
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		initialized = true;

		RTC_LOG(LS_INFO) << implementationName << " VP9 initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps";

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiVp9Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiVp9Encoder::Release()
	{
		session.Destroy();

		initialized = false;
		referenceValid = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiVp9Encoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (!initialized || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		bool keyFrame = !referenceValid;

		if (frameTypes != nullptr) {
			keyFrame |= std::any_of(frameTypes->begin(), frameTypes->end(), [](webrtc::VideoFrameType type) {
				return type == webrtc::VideoFrameType::kVideoFrameKey;
			});
		}

		std::vector<uint8_t> output;

		// VP9 codes any size, so the frame is taken as it is, and scaled only
		// if it raced with a change of size.
		const bool encoded = session.Upload(frame, static_cast<int>(codecSettings.width),
			static_cast<int>(codecSettings.height), 0) && Submit(keyFrame) && session.ReadOutput(output);

		session.DropBuffers();

		if (!encoded) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(frame.rtp_timestamp());
		image.capture_time_ms_ = frame.render_time_ms();
		image.ntp_time_ms_ = frame.ntp_time_ms();
		image.rotation_ = frame.rotation();

		webrtc::CodecSpecificInfo info;

		if (!outputProcessor.Process(output, keyFrame, image, info)) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		// The frame just encoded is the reference of the next one.
		referenceValid = true;
		current = 1 - current;
		ratesChanged = false;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiVp9Encoder::Submit(bool keyFrame)
	{
		if (keyFrame) {
			VAEncSequenceParameterBufferVP9 sequence = {};
			FillSequence(sequence);

			if (!session.AddBuffer(VAEncSequenceParameterBufferType, &sequence, sizeof(sequence))) {
				return false;
			}
		}

		// No initial quantizer: VP9 rate control picks its own, as FFmpeg
		// leaves it.
		if ((keyFrame || ratesChanged) && !session.AddRateControl(bitrateBps, framerate, 0)) {
			return false;
		}

		VAEncPictureParameterBufferVP9 picture = {};
		FillPicture(picture, keyFrame);

		return session.AddBuffer(VAEncPictureParameterBufferType, &picture, sizeof(picture)) && session.Encode();
	}

	void VaapiVp9Encoder::FillSequence(VAEncSequenceParameterBufferVP9 & sequence) const
	{
		sequence.max_frame_width = codecSettings.width;
		sequence.max_frame_height = codecSettings.height;
		// Key frames only where asked for.
		sequence.kf_auto = 0;
		sequence.kf_min_dist = 1;
		sequence.kf_max_dist = kKeyFrameInterval;
		sequence.intra_period = kKeyFrameInterval;
		sequence.bits_per_second = bitrateBps;
	}

	void VaapiVp9Encoder::FillPicture(VAEncPictureParameterBufferVP9 & picture, bool keyFrame) const
	{
		picture.frame_width_src = codecSettings.width;
		picture.frame_height_src = codecSettings.height;
		picture.frame_width_dst = codecSettings.width;
		picture.frame_height_dst = codecSettings.height;
		picture.reconstructed_frame = session.Reconstructed(current);
		picture.coded_buf = session.CodedBuffer();

		for (VASurfaceID & reference : picture.reference_frames) {
			reference = VA_INVALID_SURFACE;
		}

		if (keyFrame) {
			picture.ref_flags.bits.force_kf = 1;
			picture.pic_flags.bits.frame_type = kKeyFrameType;
		}
		else {
			picture.reference_frames[kLastSlot] = session.Reconstructed(1 - current);
			// The last frame is the only reference, in list 0.
			picture.ref_flags.bits.ref_frame_ctrl_l0 = 1;
			picture.ref_flags.bits.ref_last_idx = kLastSlot;
			picture.pic_flags.bits.frame_type = kInterFrameType;
		}

		picture.pic_flags.bits.show_frame = 1;
		// Every slot holds this frame afterwards.
		picture.refresh_frame_flags = 0xFF;

		picture.luma_ac_qindex = kQIndex;
		picture.filter_level = kFilterLevel;
		picture.sharpness_level = kSharpnessLevel;
	}

	void VaapiVp9Encoder::SetRates(const RateControlParameters & parameters)
	{
		const uint32_t bitrate = parameters.bitrate.get_sum_bps();

		// A zero bitrate pauses the stream; the encoder gets no frames then.
		if (bitrate == 0) {
			return;
		}

		bitrateBps = bitrate;

		if (parameters.framerate_fps >= 1.0) {
			framerate = static_cast<uint32_t>(parameters.framerate_fps + 0.5);
		}

		// Applied with the next frame.
		ratesChanged = true;
	}

	webrtc::VideoEncoder::EncoderInfo VaapiVp9Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();

		return info;
	}
}
