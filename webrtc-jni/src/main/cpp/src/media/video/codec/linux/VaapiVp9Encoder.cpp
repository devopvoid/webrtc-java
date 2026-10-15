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

		// The reference frames LAST and GOLDEN in ref_frame_ctrl_l0.
		constexpr uint32_t kLastFrame = 1;
		constexpr uint32_t kGoldenFrame = 2;

		// What a frame that cannot be encoded returns: libvpx takes over the
		// stream, which stays in VP9, rather than WebRTC changing the codec.
		constexpr int32_t kEncodeFailed = WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;

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
		// Temporal layers; spatial ones are left to libvpx.
		if (settings->VP9().numberOfSpatialLayers > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		const int temporalLayers = settings->VP9().numberOfTemporalLayers;
		const webrtc::ScalabilityMode mode = settings->GetScalabilityMode().value_or(temporalLayers == 3
			? webrtc::ScalabilityMode::kL1T3
			: temporalLayers == 2 ? webrtc::ScalabilityMode::kL1T2 : webrtc::ScalabilityMode::kL1T1);

		if (mode != webrtc::ScalabilityMode::kL1T1 && mode != webrtc::ScalabilityMode::kL1T2
			&& mode != webrtc::ScalabilityMode::kL1T3)
		{
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		codecSettings = *settings;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		referenceValid = false;
		outputProcessor.Reset();
		layers.Configure(mode, bitrateBps, VaapiTemporalLayers::LayerRateControl(library, VAProfileVP9Profile0,
			entrypoint, webrtc::ScalabilityModeToNumTemporalLayers(mode)));

		if (!outputProcessor.SetScalabilityMode(mode)) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		const uint32_t surfaceWidth = Align(codecSettings.width, kSurfaceAlignment);
		const uint32_t surfaceHeight = Align(codecSettings.height, kSurfaceAlignment);

		if (!session.Create(VAProfileVP9Profile0, entrypoint, surfaceWidth, surfaceHeight, surfaceWidth, surfaceHeight,
			VA_ENC_PACKED_HEADER_NONE, VaapiTemporalLayers::kReconstructedSurfaces))
		{
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		initialized = true;

		RTC_LOG(LS_INFO) << implementationName << " VP9 initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps, " << webrtc::ScalabilityModeToString(mode);

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

		const auto layerFrame = outputProcessor.NextFrameConfig(keyFrame);

		// No layer is encoded now, as when WebRTC gives none a bitrate.
		if (!layerFrame) {
			return WEBRTC_VIDEO_CODEC_OK;
		}

		const auto layerPlan = layers.Plan(keyFrame, *layerFrame);

		if (!layerPlan) {
			RTC_LOG(LS_WARNING) << implementationName << " VP9: a frame refers to a slot that holds no frame";
			return kEncodeFailed;
		}

		std::vector<uint8_t> output;

		// VP9 codes any size, so the frame is taken as it is, and scaled only
		// if it raced with a change of size.
		const bool encoded = session.Upload(frame, static_cast<int>(codecSettings.width),
			static_cast<int>(codecSettings.height), 0) && Submit(keyFrame, *layerPlan) && session.ReadOutput(output);

		session.DropBuffers();


		if (!encoded) {
			return kEncodeFailed;
		}

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(frame.rtp_timestamp());
		image.capture_time_ms_ = frame.render_time_ms();
		image.ntp_time_ms_ = frame.ntp_time_ms();
		image.rotation_ = frame.rotation();

		webrtc::CodecSpecificInfo info;

		if (!outputProcessor.Process(output, keyFrame, *layerFrame, image, info)) {
			return kEncodeFailed;
		}

		layers.Commit(*layerPlan);
		referenceValid = true;
		ratesChanged = false;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiVp9Encoder::Submit(bool keyFrame, const VaapiTemporalLayers::Frame & frame)
	{
		if (keyFrame) {
			VAEncSequenceParameterBufferVP9 sequence = {};
			FillSequence(sequence);

			if (!session.AddBuffer(VAEncSequenceParameterBufferType, &sequence, sizeof(sequence))) {
				return false;
			}
		}

		if ((keyFrame || ratesChanged) && !layers.AddRateControl(session, bitrateBps, framerate, keyFrame)) {
			return false;
		}

		VAEncPictureParameterBufferVP9 picture = {};
		FillPicture(picture, keyFrame, frame);

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

	void VaapiVp9Encoder::FillPicture(VAEncPictureParameterBufferVP9 & picture, bool keyFrame,
		const VaapiTemporalLayers::Frame & frame) const
	{
		picture.frame_width_src = codecSettings.width;
		picture.frame_height_src = codecSettings.height;
		picture.frame_width_dst = codecSettings.width;
		picture.frame_height_dst = codecSettings.height;
		picture.reconstructed_frame = session.Reconstructed(frame.surface);
		picture.coded_buf = session.CodedBuffer();

		for (VASurfaceID & reference : picture.reference_frames) {
			reference = VA_INVALID_SURFACE;
		}

		if (keyFrame) {
			picture.ref_flags.bits.force_kf = 1;
			picture.pic_flags.bits.frame_type = kKeyFrameType;
		}
		else {
			for (int slot = 0; slot < layers.SlotCount(); slot++) {
				if (layers.Surface(slot) >= 0) {
					picture.reference_frames[slot] = session.Reconstructed(layers.Surface(slot));
				}
			}

			// The first reference is the last frame, a second one golden; both
			// in list 0.
			picture.ref_flags.bits.ref_frame_ctrl_l0 = kLastFrame;
			picture.ref_flags.bits.ref_last_idx = static_cast<uint32_t>(frame.references[0]);

			if (frame.references.size() > 1) {
				picture.ref_flags.bits.ref_frame_ctrl_l0 |= kGoldenFrame;
				picture.ref_flags.bits.ref_gf_idx = static_cast<uint32_t>(frame.references[1]);
			}

			picture.pic_flags.bits.frame_type = kInterFrameType;
		}

		picture.ref_flags.bits.temporal_id = static_cast<uint32_t>(frame.temporalId);
		picture.pic_flags.bits.show_frame = 1;

		// VP9 takes motion vectors from the frame decoded before, and carries
		// probabilities from frame to frame, neither of which a receiver
		// without the upper layers has; error resilience leaves both out, as
		// libvpx does with layers.
		if (layers.Count() > 1) {
			picture.pic_flags.bits.error_resilient_mode = 1;
		}
		picture.refresh_frame_flags = frame.refreshFlags;

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
		outputProcessor.OnRatesUpdated(parameters.bitrate);
		layers.OnRatesUpdated(parameters.bitrate);

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

		layers.FillFpsAllocation(info);

		return info;
	}
}
