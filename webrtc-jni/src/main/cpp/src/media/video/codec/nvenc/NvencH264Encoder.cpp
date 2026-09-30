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

#include "media/video/codec/nvenc/NvencH264Encoder.h"
#include "media/video/codec/nvenc/CudaContextScope.h"

#include "api/video/encoded_image.h"
#include "api/video/i420_buffer.h"
#include "modules/video_coding/codecs/interface/common_constants.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert_from.h"

#include <algorithm>
#include <span>

namespace jni
{
	namespace
	{
		// The QP thresholds of WebRTC's own H.264 encoder.
		constexpr int kLowH264QpThreshold = 24;
		constexpr int kHighH264QpThreshold = 37;
	}

	NvencH264Encoder::NvencH264Encoder(NvencLibrary & library, const webrtc::SdpVideoFormat & format) :
		library(library),
		api(library.Api()),
		implementationName("NVENC (" + library.DeviceName() + ")"),
		context(nullptr),
		encoder(nullptr),
		inputBuffer(nullptr),
		outputBuffer(nullptr),
		initParams(),
		config(),
		codecSettings(),
		bitrateBps(0),
		framerate(30),
		frameCount(0),
		callback(nullptr),
		packetizationMode(webrtc::H264PacketizationMode::NonInterleaved)
	{
		auto mode = format.parameters.find("packetization-mode");

		if (mode == format.parameters.end() || mode->second != "1") {
			packetizationMode = webrtc::H264PacketizationMode::SingleNalUnit;
		}
	}

	NvencH264Encoder::~NvencH264Encoder()
	{
		Release();
	}

	int32_t NvencH264Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// NV12 has chroma at half the resolution in both directions.
		if (settings->width % 2 != 0 || settings->height % 2 != 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		codecSettings = *settings;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		frameCount = 0;

		if (!library.RetainContext(&context)) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to retain the CUDA context";
			context = nullptr;
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		if (!OpenSession() || !Configure()) {
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_INFO) << implementationName << " initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps";

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool NvencH264Encoder::OpenSession()
	{
		CudaContextScope scope(library, context);

		NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS params = {};
		params.version = NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;
		params.deviceType = NV_ENC_DEVICE_TYPE_CUDA;
		params.device = context;
		params.apiVersion = NVENCAPI_VERSION;

		// Fails when the GPU has no encoder sessions left, which consumer
		// GPUs limit.
		NVENCSTATUS status = api.nvEncOpenEncodeSessionEx(&params, &encoder);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to open a session, status " << status;
			encoder = nullptr;
			return false;
		}

		return true;
	}

	bool NvencH264Encoder::Configure()
	{
		CudaContextScope scope(library, context);

		NV_ENC_PRESET_CONFIG presetConfig = {};
		presetConfig.version = NV_ENC_PRESET_CONFIG_VER;
		presetConfig.presetCfg.version = NV_ENC_CONFIG_VER;

		NVENCSTATUS status = api.nvEncGetEncodePresetConfigEx(encoder, NV_ENC_CODEC_H264_GUID,
			NV_ENC_PRESET_P4_GUID, NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY, &presetConfig);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to get the preset, status " << status;
			return false;
		}

		config = presetConfig.presetCfg;
		config.version = NV_ENC_CONFIG_VER;
		config.profileGUID = NV_ENC_H264_PROFILE_BASELINE_GUID;

		// No B-frames, and key frames only when WebRTC asks for them.
		config.gopLength = NVENC_INFINITE_GOPLENGTH;
		config.frameIntervalP = 1;

		NV_ENC_CONFIG_H264 & h264 = config.encodeCodecConfig.h264Config;
		h264.idrPeriod = NVENC_INFINITE_GOPLENGTH;
		// A receiver joining later needs them with the key frame it starts from.
		h264.repeatSPSPPS = 1;
		h264.outputAUD = 0;
		h264.sliceMode = 0;
		h264.sliceModeData = 0;
		h264.chromaFormatIDC = 1;
		h264.level = NV_ENC_LEVEL_AUTOSELECT;
		// Baseline has no CABAC.
		h264.entropyCodingMode = NV_ENC_H264_ENTROPY_CODING_MODE_CAVLC;

		initParams = {};
		initParams.version = NV_ENC_INITIALIZE_PARAMS_VER;
		initParams.encodeGUID = NV_ENC_CODEC_H264_GUID;
		initParams.presetGUID = NV_ENC_PRESET_P4_GUID;
		initParams.tuningInfo = NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY;
		initParams.encodeWidth = codecSettings.width;
		initParams.encodeHeight = codecSettings.height;
		initParams.darWidth = codecSettings.width;
		initParams.darHeight = codecSettings.height;
		initParams.maxEncodeWidth = codecSettings.width;
		initParams.maxEncodeHeight = codecSettings.height;
		initParams.enablePTD = 1;
		initParams.enableEncodeAsync = 0;
		initParams.encodeConfig = &config;

		ApplyRates();

		status = api.nvEncInitializeEncoder(encoder, &initParams);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to initialize the encoder, status " << status;
			return false;
		}

		NV_ENC_CREATE_INPUT_BUFFER input = {};
		input.version = NV_ENC_CREATE_INPUT_BUFFER_VER;
		input.width = codecSettings.width;
		input.height = codecSettings.height;
		input.bufferFmt = NV_ENC_BUFFER_FORMAT_NV12;

		status = api.nvEncCreateInputBuffer(encoder, &input);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to create the input buffer, status " << status;
			return false;
		}

		inputBuffer = input.inputBuffer;

		NV_ENC_CREATE_BITSTREAM_BUFFER output = {};
		output.version = NV_ENC_CREATE_BITSTREAM_BUFFER_VER;

		status = api.nvEncCreateBitstreamBuffer(encoder, &output);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to create the output buffer, status " << status;
			return false;
		}

		outputBuffer = output.bitstreamBuffer;

		return true;
	}

	void NvencH264Encoder::ApplyRates()
	{
		initParams.frameRateNum = framerate;
		initParams.frameRateDen = 1;

		NV_ENC_RC_PARAMS & rc = config.rcParams;
		rc.rateControlMode = NV_ENC_PARAMS_RC_CBR;
		rc.averageBitRate = bitrateBps;
		rc.maxBitRate = bitrateBps;
		// A buffer of one frame keeps the size of each frame close to the
		// average, which is what keeps latency low.
		rc.vbvBufferSize = bitrateBps / framerate;
		rc.vbvInitialDelay = rc.vbvBufferSize;
	}

	int32_t NvencH264Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t NvencH264Encoder::Release()
	{
		DestroySession();

		if (context != nullptr) {
			library.ReleaseContext();
			context = nullptr;
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	void NvencH264Encoder::DestroySession()
	{
		if (encoder == nullptr) {
			return;
		}

		CudaContextScope scope(library, context);

		if (inputBuffer != nullptr) {
			api.nvEncDestroyInputBuffer(encoder, inputBuffer);
			inputBuffer = nullptr;
		}
		if (outputBuffer != nullptr) {
			api.nvEncDestroyBitstreamBuffer(encoder, outputBuffer);
			outputBuffer = nullptr;
		}

		api.nvEncDestroyEncoder(encoder);
		encoder = nullptr;
	}

	int32_t NvencH264Encoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (encoder == nullptr || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		bool keyFrameRequested = false;

		if (frameTypes != nullptr) {
			keyFrameRequested = std::any_of(frameTypes->begin(), frameTypes->end(),
				[](webrtc::VideoFrameType type) {
					return type == webrtc::VideoFrameType::kVideoFrameKey;
				});
		}

		CudaContextScope scope(library, context);

		uint32_t pitch = 0;

		if (!CopyToInput(frame, &pitch)) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		NV_ENC_PIC_PARAMS picture = {};
		picture.version = NV_ENC_PIC_PARAMS_VER;
		picture.inputWidth = codecSettings.width;
		picture.inputHeight = codecSettings.height;
		picture.inputPitch = pitch;
		picture.inputBuffer = inputBuffer;
		picture.outputBitstream = outputBuffer;
		picture.bufferFmt = NV_ENC_BUFFER_FORMAT_NV12;
		picture.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
		picture.inputTimeStamp = frameCount++;

		if (keyFrameRequested) {
			picture.encodePicFlags = NV_ENC_PIC_FLAG_FORCEIDR | NV_ENC_PIC_FLAG_OUTPUT_SPSPPS;
		}

		NVENCSTATUS status = api.nvEncEncodePicture(encoder, &picture);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to encode a frame, status " << status;
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		NV_ENC_LOCK_BITSTREAM bitstream = {};
		bitstream.version = NV_ENC_LOCK_BITSTREAM_VER;
		bitstream.outputBitstream = outputBuffer;

		status = api.nvEncLockBitstream(encoder, &bitstream);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to read an encoded frame, status " << status;
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		const uint8_t * data = static_cast<const uint8_t *>(bitstream.bitstreamBufferPtr);
		const size_t size = bitstream.bitstreamSizeInBytes;
		const bool keyFrame = bitstream.pictureType == NV_ENC_PIC_TYPE_IDR
			|| bitstream.pictureType == NV_ENC_PIC_TYPE_I;

		webrtc::scoped_refptr<webrtc::EncodedImageBuffer> encoded = webrtc::EncodedImageBuffer::Create(data, size);

		api.nvEncUnlockBitstream(encoder, outputBuffer);

		webrtc::EncodedImage image;
		image.SetEncodedData(encoded);
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(frame.rtp_timestamp());
		image.capture_time_ms_ = frame.render_time_ms();
		image.ntp_time_ms_ = frame.ntp_time_ms();
		image.rotation_ = frame.rotation();
		image.set_frame_type(keyFrame
			? webrtc::VideoFrameType::kVideoFrameKey
			: webrtc::VideoFrameType::kVideoFrameDelta);

		// The bitstream QP, which quality scaling compares with its
		// thresholds, rather than NVENC's average.
		bitstreamParser.ParseBitstream(std::span<const uint8_t>(encoded->data(), encoded->size()));
		image.qp_ = bitstreamParser.GetLastSliceQp().value_or(-1);

		webrtc::CodecSpecificInfo info;
		info.codecType = webrtc::kVideoCodecH264;
		info.codecSpecific.H264.packetization_mode = packetizationMode;
		info.codecSpecific.H264.temporal_idx = webrtc::kNoTemporalIdx;
		info.codecSpecific.H264.base_layer_sync = false;
		info.codecSpecific.H264.idr_frame = keyFrame;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool NvencH264Encoder::CopyToInput(const webrtc::VideoFrame & frame, uint32_t * pitch)
	{
		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420 = frame.video_frame_buffer()->ToI420();

		if (!i420) {
			return false;
		}

		const int width = static_cast<int>(codecSettings.width);
		const int height = static_cast<int>(codecSettings.height);

		// WebRTC initializes the encoder again when the frame size changes,
		// so a frame of another size is one that raced with that.
		if (i420->width() != width || i420->height() != height) {
			webrtc::scoped_refptr<webrtc::I420Buffer> scaled = webrtc::I420Buffer::Create(width, height);
			scaled->ScaleFrom(*i420);
			i420 = scaled;
		}

		NV_ENC_LOCK_INPUT_BUFFER lock = {};
		lock.version = NV_ENC_LOCK_INPUT_BUFFER_VER;
		lock.inputBuffer = inputBuffer;

		NVENCSTATUS status = api.nvEncLockInputBuffer(encoder, &lock);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to lock the input buffer, status " << status;
			return false;
		}

		uint8_t * y = static_cast<uint8_t *>(lock.bufferDataPtr);
		uint8_t * uv = y + static_cast<size_t>(lock.pitch) * height;

		libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
			i420->DataV(), i420->StrideV(), y, lock.pitch, uv, lock.pitch, width, height);

		api.nvEncUnlockInputBuffer(encoder, inputBuffer);

		*pitch = lock.pitch;

		return true;
	}

	void NvencH264Encoder::SetRates(const RateControlParameters & parameters)
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

		if (encoder == nullptr) {
			return;
		}

		ApplyRates();

		CudaContextScope scope(library, context);

		NV_ENC_RECONFIGURE_PARAMS reconfigure = {};
		reconfigure.version = NV_ENC_RECONFIGURE_PARAMS_VER;
		reconfigure.reInitEncodeParams = initParams;
		reconfigure.resetEncoder = 0;
		reconfigure.forceIDR = 0;

		NVENCSTATUS status = api.nvEncReconfigureEncoder(encoder, &reconfigure);

		if (status != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: failed to change the rates, status " << status;
		}
	}

	webrtc::VideoEncoder::EncoderInfo NvencH264Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = ScalingSettings(kLowH264QpThreshold, kHighH264QpThreshold);
		// NV12 needs even dimensions.
		info.requested_resolution_alignment = 2;

		return info;
	}
}
