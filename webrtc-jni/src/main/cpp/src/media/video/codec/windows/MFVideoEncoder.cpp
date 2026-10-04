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

#include "media/video/codec/windows/MFVideoEncoder.h"
#include "media/video/codec/windows/MFEncoderUtils.h"
#include "platform/windows/ComInitializer.h"

#include "api/video/encoded_image.h"
#include "api/video/i420_buffer.h"
#include "api/video_codecs/scalability_mode.h"
#include "modules/video_coding/codecs/interface/common_constants.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "rtc_base/time_utils.h"
#include "third_party/libyuv/include/libyuv/convert_from.h"

#include <codecapi.h>
#include <mferror.h>

#include <algorithm>
#include <chrono>
#include <span>

using Microsoft::WRL::ComPtr;

namespace jni
{
	namespace
	{
		// How long a frame may wait for the transform to take input before
		// it is dropped.
		constexpr std::chrono::milliseconds kInputTimeout(20);

		// A key frame every 100 seconds at 30 fps. WebRTC asks for key
		// frames whenever a receiver needs one, so they are rarely due to
		// this.
		constexpr UINT32 kGopSize = 3000;

		// Media Foundation counts time in 100 ns units.
		constexpr LONGLONG kUnitsPerSecond = 10000000;

		// Initializes COM for the calling thread, once. Media Foundation
		// transforms are free-threaded, so the apartment a thread already
		// has does as well.
		bool EnsureComInitialized()
		{
			thread_local std::unique_ptr<ComInitializer> initializer;

			if (!initializer) {
				try {
					initializer = std::make_unique<ComInitializer>();
				}
				catch (...) {
					return false;
				}
			}

			return true;
		}

		void SetCodecValue(ICodecAPI * codecApi, const GUID & api, UINT32 value)
		{
			VARIANT variant;
			VariantInit(&variant);
			variant.vt = VT_UI4;
			variant.ulVal = value;

			// Not every transform supports every setting; those it does
			// not support keep their default.
			HRESULT hr = codecApi->SetValue(&api, &variant);

			if (FAILED(hr)) {
				RTC_LOG(LS_INFO) << "Media Foundation encoder: a codec setting is not supported, hr=" << hr;
			}
		}

		void SetCodecFlag(ICodecAPI * codecApi, const GUID & api, bool value)
		{
			VARIANT variant;
			VariantInit(&variant);
			variant.vt = VT_BOOL;
			variant.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;

			HRESULT hr = codecApi->SetValue(&api, &variant);

			if (FAILED(hr)) {
				RTC_LOG(LS_INFO) << "Media Foundation encoder: a codec flag is not supported, hr=" << hr;
			}
		}
	}

	MFVideoEncoder::MFVideoEncoder(webrtc::VideoCodecType codec, const webrtc::SdpVideoFormat & format) :
		codec(codec),
		implementationName("MediaFoundation"),
		inputStreamId(0),
		outputStreamId(0),
		codecSettings(),
		bitrateBps(0),
		framerate(30),
		callback(nullptr),
		failed(false),
		inputRequests(0),
		lastSampleTime(-1),
		keyFrameRequested(false),
		outputProcessor(codec, format)
	{
	}

	MFVideoEncoder::~MFVideoEncoder()
	{
		Release();
	}

	int32_t MFVideoEncoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// Hardware encoders produce a single layer; spatial or temporal
		// layers are left to the software encoder.
		if (settings->GetScalabilityMode().value_or(webrtc::ScalabilityMode::kL1T1) != webrtc::ScalabilityMode::kL1T1) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		// NV12 has chroma at half the resolution in both directions. The first
		// frame of a source can be odd, since WebRTC asks the source for
		// requested_resolution_alignment only once the encoder has been set up.
		// Refusing it would leave the whole session to the software encoder, so
		// the encoder takes the even size below it and drops the last row and
		// column of such frames.
		if (settings->width < 2 || settings->height < 2) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		if (!EnsureComInitialized()) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		try {
			mfInitializer = std::make_unique<MFInitializer>();
		}
		catch (...) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		codecSettings = *settings;
		codecSettings.width &= ~1;
		codecSettings.height &= ~1;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);

		HRESULT hr = CreateTransform();

		if (SUCCEEDED(hr)) {
			hr = ConfigureTypes();
		}
		if (SUCCEEDED(hr)) {
			ConfigureCodec();

			ComPtr<IMFMediaEventGenerator> generator;
			hr = transform.As(&generator);

			if (SUCCEEDED(hr)) {
				hr = MFTransformEvents::Start(generator.Get(), this, &events);
			}
		}
		if (SUCCEEDED(hr)) {
			hr = transform->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
		}
		if (SUCCEEDED(hr)) {
			hr = transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
		}

		if (FAILED(hr)) {
			RTC_LOG(LS_WARNING) << "Media Foundation encoder failed to initialize, hr=" << hr;

			Release();

			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_INFO) << "Media Foundation encoder " << implementationName << " initialized: "
			<< codecSettings.width << "x" << codecSettings.height << " at " << bitrateBps << " bps";

		return WEBRTC_VIDEO_CODEC_OK;
	}

	HRESULT MFVideoEncoder::CreateTransform()
	{
		std::vector<ComPtr<IMFActivate>> encoders;

		HRESULT hr = EnumerateHardwareEncoders(codec == webrtc::kVideoCodecAV1 ? MFVideoFormat_AV1 : MFVideoFormat_H264,
			encoders);

		if (FAILED(hr)) {
			return hr;
		}
		if (encoders.empty()) {
			return MF_E_TOPO_CODEC_NOT_FOUND;
		}

		// The first is the one the system ranks best.
		activate = encoders.front();
		implementationName = "MediaFoundation (" + GetTransformName(activate.Get()) + ")";

		hr = activate->ActivateObject(IID_PPV_ARGS(&transform));

		if (FAILED(hr)) {
			return hr;
		}

		ComPtr<IMFAttributes> attributes;
		hr = transform->GetAttributes(&attributes);

		if (FAILED(hr)) {
			return hr;
		}

		UINT32 async = FALSE;
		attributes->GetUINT32(MF_TRANSFORM_ASYNC, &async);

		// Hardware transforms are asynchronous, and that is all this
		// encoder drives.
		if (!async) {
			return E_NOTIMPL;
		}

		hr = attributes->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);

		if (FAILED(hr)) {
			return hr;
		}

		attributes->SetUINT32(MF_LOW_LATENCY, TRUE);

		// Optional: without it, the encoder keeps its defaults.
		transform.As(&codecApi);

		hr = transform->GetStreamIDs(1, &inputStreamId, 1, &outputStreamId);

		if (hr == E_NOTIMPL) {
			// Fixed stream IDs, which start at 0.
			inputStreamId = 0;
			outputStreamId = 0;
			hr = S_OK;
		}

		return hr;
	}

	HRESULT MFVideoEncoder::ConfigureTypes()
	{
		const UINT32 width = codecSettings.width;
		const UINT32 height = codecSettings.height;

		// An encoder takes its output type first.
		ComPtr<IMFMediaType> outputType;
		HRESULT hr = MFCreateMediaType(&outputType);

		if (FAILED(hr)) {
			return hr;
		}

		outputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
		outputType->SetGUID(MF_MT_SUBTYPE, codec == webrtc::kVideoCodecAV1 ? MFVideoFormat_AV1 : MFVideoFormat_H264);
		outputType->SetUINT32(MF_MT_AVG_BITRATE, bitrateBps);
		outputType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
		MFSetAttributeSize(outputType.Get(), MF_MT_FRAME_SIZE, width, height);
		MFSetAttributeRatio(outputType.Get(), MF_MT_FRAME_RATE, framerate, 1);
		MFSetAttributeRatio(outputType.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);

		if (codec == webrtc::kVideoCodecAV1) {
			// Main profile, 8-bit 4:2:0: AV1 profile 0, what WebRTC offers.
			outputType->SetUINT32(MF_MT_VIDEO_PROFILE, eAVEncAV1VProfile_Main_420_8);

			hr = transform->SetOutputType(outputStreamId, outputType.Get(), 0);
		}
		else {
			// Constrained Baseline is what peers expect, and is a subset of
			// Baseline too. Older encoders only know Baseline, which peers
			// decode as well, since WebRTC encoders use no Baseline-only
			// tools.
			outputType->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_ConstrainedBase);

			hr = transform->SetOutputType(outputStreamId, outputType.Get(), 0);

			if (FAILED(hr)) {
				outputType->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base);

				hr = transform->SetOutputType(outputStreamId, outputType.Get(), 0);
			}
		}
		if (FAILED(hr)) {
			return hr;
		}

		ComPtr<IMFMediaType> inputType;
		hr = MFCreateMediaType(&inputType);

		if (FAILED(hr)) {
			return hr;
		}

		inputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
		inputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
		inputType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
		inputType->SetUINT32(MF_MT_DEFAULT_STRIDE, width);
		MFSetAttributeSize(inputType.Get(), MF_MT_FRAME_SIZE, width, height);
		MFSetAttributeRatio(inputType.Get(), MF_MT_FRAME_RATE, framerate, 1);
		MFSetAttributeRatio(inputType.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);

		return transform->SetInputType(inputStreamId, inputType.Get(), 0);
	}

	void MFVideoEncoder::ConfigureCodec()
	{
		if (!codecApi) {
			return;
		}

		// One frame in, one frame out, and no frames that reference later
		// ones: what real-time communication needs.
		SetCodecFlag(codecApi.Get(), CODECAPI_AVLowLatencyMode, true);
		SetCodecValue(codecApi.Get(), CODECAPI_AVEncMPVDefaultBPictureCount, 0);
		SetCodecValue(codecApi.Get(), CODECAPI_AVEncMPVGOPSize, kGopSize);
		SetCodecValue(codecApi.Get(), CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
		SetCodecValue(codecApi.Get(), CODECAPI_AVEncCommonMeanBitRate, bitrateBps);
	}

	int32_t MFVideoEncoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t MFVideoEncoder::Release()
	{
		ShutdownTransform();

		mfInitializer.reset();

		{
			std::lock_guard<std::mutex> lock(inputMutex);
			inputRequests = 0;
		}
		{
			std::lock_guard<std::mutex> lock(pendingMutex);
			pendingFrames.clear();
		}

		failed = false;
		lastSampleTime = -1;
		keyFrameRequested = false;

		// No events arrive any more.
		outputProcessor.Reset();

		return WEBRTC_VIDEO_CODEC_OK;
	}

	void MFVideoEncoder::ShutdownTransform()
	{
		// Detached first, so that no event handler touches the transform
		// while it shuts down, nor this encoder afterwards.
		if (events) {
			events->Stop();
			events.Reset();
		}

		if (transform) {
			transform->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
			transform->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
		}

		codecApi.Reset();
		transform.Reset();

		if (activate) {
			activate->ShutdownObject();
			activate.Reset();
		}
	}

	int32_t MFVideoEncoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		webrtc::EncodedImageCallback * encodeCallback = callback.load();

		if (!transform || encodeCallback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		if (failed) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		if (frameTypes != nullptr) {
			for (webrtc::VideoFrameType type : *frameTypes) {
				if (type == webrtc::VideoFrameType::kVideoFrameKey) {
					keyFrameRequested = true;
				}
			}
		}

		{
			std::unique_lock<std::mutex> lock(inputMutex);

			inputRequested.wait_for(lock, kInputTimeout, [this] {
				return inputRequests > 0 || failed;
			});

			if (failed) {
				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}
			if (inputRequests == 0) {
				// Still busy with earlier frames. A key frame asked for is
				// kept for the next frame that gets through.
				lock.unlock();

				encodeCallback->OnFrameDropped(frame.rtp_timestamp(), 0, true);

				return WEBRTC_VIDEO_CODEC_OK;
			}

			inputRequests--;
		}

		if (keyFrameRequested && codecApi) {
			SetCodecValue(codecApi.Get(), CODECAPI_AVEncVideoForceKeyFrame, 1);
		}
		keyFrameRequested = false;

		ComPtr<IMFSample> sample;
		HRESULT hr = CreateInputSample(frame, &sample);

		if (FAILED(hr)) {
			RTC_LOG(LS_WARNING) << "Media Foundation encoder failed to create a sample, hr=" << hr;

			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		LONGLONG sampleTime = 0;
		sample->GetSampleTime(&sampleTime);

		{
			std::lock_guard<std::mutex> lock(pendingMutex);
			pendingFrames[sampleTime] = PendingFrame {
				frame.rtp_timestamp(),
				frame.render_time_ms(),
				frame.ntp_time_ms(),
				frame.rotation()
			};
		}

		hr = transform->ProcessInput(inputStreamId, sample.Get(), 0);

		if (FAILED(hr)) {
			RTC_LOG(LS_WARNING) << "Media Foundation encoder rejected a frame, hr=" << hr;

			{
				std::lock_guard<std::mutex> lock(pendingMutex);
				pendingFrames.erase(sampleTime);
			}

			failed = true;

			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	HRESULT MFVideoEncoder::CreateInputSample(const webrtc::VideoFrame & frame, IMFSample ** sample)
	{
		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420 = frame.video_frame_buffer()->ToI420();

		if (!i420) {
			return E_FAIL;
		}

		const int width = static_cast<int>(codecSettings.width);
		const int height = static_cast<int>(codecSettings.height);

		// WebRTC initializes the encoder again when the frame size changes,
		// so a frame of another size is one that raced with that. An odd frame
		// is cropped to the even size the encoder took, which needs no copy.
		const bool cropped = i420->width() - width >= 0 && i420->width() - width <= 1
			&& i420->height() - height >= 0 && i420->height() - height <= 1;

		if (!cropped) {
			webrtc::scoped_refptr<webrtc::I420Buffer> scaled = webrtc::I420Buffer::Create(width, height);
			scaled->ScaleFrom(*i420);
			i420 = scaled;
		}

		const DWORD size = static_cast<DWORD>(width * height * 3 / 2);

		ComPtr<IMFMediaBuffer> buffer;
		HRESULT hr = MFCreateMemoryBuffer(size, &buffer);

		if (FAILED(hr)) {
			return hr;
		}

		BYTE * data = nullptr;
		hr = buffer->Lock(&data, nullptr, nullptr);

		if (FAILED(hr)) {
			return hr;
		}

		libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
			i420->DataV(), i420->StrideV(), data, width, data + width * height, width, width, height);

		buffer->Unlock();
		buffer->SetCurrentLength(size);

		ComPtr<IMFSample> input;
		hr = MFCreateSample(&input);

		if (FAILED(hr)) {
			return hr;
		}

		hr = input->AddBuffer(buffer.Get());

		if (FAILED(hr)) {
			return hr;
		}

		// The sample time identifies the frame when its output arrives, so
		// it has to be unique.
		LONGLONG sampleTime = frame.timestamp_us() * 10;

		if (sampleTime <= lastSampleTime) {
			sampleTime = lastSampleTime + 1;
		}

		lastSampleTime = sampleTime;

		input->SetSampleTime(sampleTime);
		input->SetSampleDuration(kUnitsPerSecond / framerate);

		*sample = input.Detach();

		return S_OK;
	}

	void MFVideoEncoder::SetRates(const RateControlParameters & parameters)
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

		if (codecApi) {
			SetCodecValue(codecApi.Get(), CODECAPI_AVEncCommonMeanBitRate, bitrateBps);
		}
	}

	webrtc::VideoEncoder::EncoderInfo MFVideoEncoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		// NV12 needs even dimensions.
		info.requested_resolution_alignment = 2;

		return info;
	}

	void MFVideoEncoder::OnTransformEvent(MediaEventType type, HRESULT status)
	{
		switch (type) {
			case METransformNeedInput:
			{
				std::lock_guard<std::mutex> lock(inputMutex);
				inputRequests++;
			}
			inputRequested.notify_one();
			break;

			case METransformHaveOutput:
				ProcessOutput();
				break;

			case MEError:
				RTC_LOG(LS_WARNING) << "Media Foundation encoder failed, hr=" << status;

				failed = true;
				inputRequested.notify_one();
				break;

			default:
				break;
		}
	}

	void MFVideoEncoder::ProcessOutput()
	{
		MFT_OUTPUT_STREAM_INFO info = {};
		HRESULT hr = transform->GetOutputStreamInfo(outputStreamId, &info);

		if (FAILED(hr)) {
			failed = true;
			return;
		}

		ComPtr<IMFSample> ownSample;

		if (!(info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES))) {
			ComPtr<IMFMediaBuffer> buffer;

			if (FAILED(MFCreateSample(&ownSample)) || FAILED(MFCreateMemoryBuffer(info.cbSize, &buffer)) ||
				FAILED(ownSample->AddBuffer(buffer.Get())))
			{
				failed = true;
				return;
			}
		}

		MFT_OUTPUT_DATA_BUFFER output = {};
		output.dwStreamID = outputStreamId;
		output.pSample = ownSample.Get();

		DWORD status = 0;
		hr = transform->ProcessOutput(0, 1, &output, &status);

		if (output.pEvents != nullptr) {
			output.pEvents->Release();
		}

		// A sample the transform provided is ours to release.
		ComPtr<IMFSample> sample;

		if (ownSample) {
			sample = ownSample;
		}
		else if (output.pSample != nullptr) {
			sample.Attach(output.pSample);
		}

		if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
			// The transform settled its output format; it has to be set
			// again before output continues.
			ComPtr<IMFMediaType> type;

			if (FAILED(transform->GetOutputAvailableType(outputStreamId, 0, &type)) ||
				FAILED(transform->SetOutputType(outputStreamId, type.Get(), 0)))
			{
				failed = true;
			}
			return;
		}
		if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
			return;
		}
		if (FAILED(hr) || !sample) {
			RTC_LOG(LS_WARNING) << "Media Foundation encoder failed to produce output, hr=" << hr;

			failed = true;
			return;
		}

		DeliverOutput(sample.Get());
	}

	void MFVideoEncoder::DeliverOutput(IMFSample * sample)
	{
		LONGLONG sampleTime = 0;
		sample->GetSampleTime(&sampleTime);

		UINT32 cleanPoint = FALSE;
		bool keyFrame = SUCCEEDED(sample->GetUINT32(MFSampleExtension_CleanPoint, &cleanPoint)) && cleanPoint;

		ComPtr<IMFMediaBuffer> buffer;
		BYTE * data = nullptr;
		DWORD length = 0;

		if (FAILED(sample->ConvertToContiguousBuffer(&buffer)) || FAILED(buffer->Lock(&data, nullptr, &length))) {
			failed = true;
			return;
		}

		std::span<const uint8_t> bitstream(data, length);

		PendingFrame pending;

		{
			std::lock_guard<std::mutex> lock(pendingMutex);

			auto found = pendingFrames.find(sampleTime);

			if (found == pendingFrames.end()) {
				RTC_LOG(LS_WARNING) << "Media Foundation encoder produced a frame for no input, time " << sampleTime;
				buffer->Unlock();
				return;
			}

			pending = found->second;

			// Frames the encoder skipped come out never.
			pendingFrames.erase(pendingFrames.begin(), std::next(found));
		}

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(pending.rtpTimestamp);
		image.capture_time_ms_ = pending.captureTimeMs;
		image.ntp_time_ms_ = pending.ntpTimeMs;
		image.rotation_ = pending.rotation;

		webrtc::CodecSpecificInfo info;
		const bool processed = outputProcessor.Process(bitstream, keyFrame, image, info);

		buffer->Unlock();

		if (!processed) {
			failed = true;
			return;
		}

		webrtc::EncodedImageCallback * encodeCallback = callback.load();

		if (encodeCallback != nullptr) {
			encodeCallback->OnEncodedImage(image, &info);
		}
	}
}
