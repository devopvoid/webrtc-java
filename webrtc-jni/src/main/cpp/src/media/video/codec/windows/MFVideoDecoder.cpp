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

#include "media/video/codec/windows/MFVideoDecoder.h"
#include "media/video/codec/windows/MFDecoderUtils.h"
#include "media/video/codec/windows/MFEncoderUtils.h"
#include "platform/windows/ComInitializer.h"

#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert.h"

#include <mferror.h>

#include <cstring>

using Microsoft::WRL::ComPtr;

namespace jni
{
	namespace
	{
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

		const GUID & InputFormat(webrtc::VideoCodecType codec)
		{
			return codec == webrtc::kVideoCodecAV1 ? MFVideoFormat_AV1 : MFVideoFormat_H264;
		}
	}

	MFVideoDecoder::MFVideoDecoder(webrtc::VideoCodecType codec) :
		codec(codec),
		implementationName("MediaFoundation"),
		inputStreamId(0),
		outputStreamId(0),
		stagingDesc(),
		width(0),
		height(0),
		callback(nullptr),
		sampleTime(0)
	{
	}

	MFVideoDecoder::~MFVideoDecoder()
	{
		Release();
	}

	bool MFVideoDecoder::Configure(const Settings & settings)
	{
		Release();

		if (!EnsureComInitialized()) {
			return false;
		}

		try {
			mfInitializer = std::make_unique<MFInitializer>();
		}
		catch (...) {
			return false;
		}

		HRESULT hr = CreateTransform(settings);

		if (FAILED(hr)) {
			RTC_LOG(LS_WARNING) << "Media Foundation decoder failed to configure, hr=" << hr;

			Release();

			return false;
		}

		RTC_LOG(LS_INFO) << implementationName << " configured";

		return true;
	}

	HRESULT MFVideoDecoder::CreateTransform(const Settings & settings)
	{
		HRESULT hr = CreateVideoDevice(device, context);

		if (FAILED(hr)) {
			return hr;
		}

		UINT resetToken = 0;
		hr = MFCreateDXGIDeviceManager(&resetToken, &deviceManager);

		if (SUCCEEDED(hr)) {
			hr = deviceManager->ResetDevice(device.Get(), resetToken);
		}
		if (FAILED(hr)) {
			return hr;
		}

		std::vector<ComPtr<IMFActivate>> decoders;
		hr = EnumerateDecoders(InputFormat(codec), decoders);

		if (SUCCEEDED(hr)) {
			hr = ActivateDirect3DDecoder(decoders, deviceManager.Get(), activate, transform);
		}
		if (FAILED(hr)) {
			return hr;
		}

		implementationName = "MediaFoundation (" + GetTransformName(activate.Get()) + ")";

		ComPtr<IMFAttributes> attributes;

		if (SUCCEEDED(transform->GetAttributes(&attributes))) {
			// Each frame out as soon as it is in, rather than after a few
			// frames that could reorder it.
			attributes->SetUINT32(MF_LOW_LATENCY, TRUE);
		}

		hr = transform->GetStreamIDs(1, &inputStreamId, 1, &outputStreamId);

		if (hr == E_NOTIMPL) {
			inputStreamId = 0;
			outputStreamId = 0;
		}

		ComPtr<IMFMediaType> inputType;
		hr = MFCreateMediaType(&inputType);

		if (FAILED(hr)) {
			return hr;
		}

		inputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
		inputType->SetGUID(MF_MT_SUBTYPE, InputFormat(codec));
		inputType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);

		webrtc::RenderResolution resolution = settings.max_render_resolution();

		if (resolution.Valid()) {
			MFSetAttributeSize(inputType.Get(), MF_MT_FRAME_SIZE, resolution.Width(), resolution.Height());
		}

		hr = transform->SetInputType(inputStreamId, inputType.Get(), 0);

		if (SUCCEEDED(hr)) {
			hr = SetOutputType();
		}
		if (SUCCEEDED(hr)) {
			hr = transform->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
		}
		if (SUCCEEDED(hr)) {
			hr = transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
		}

		return hr;
	}

	HRESULT MFVideoDecoder::SetOutputType()
	{
		for (DWORD index = 0;; index++) {
			ComPtr<IMFMediaType> type;
			HRESULT hr = transform->GetOutputAvailableType(outputStreamId, index, &type);

			if (FAILED(hr)) {
				// No NV12 among the types, or no more types.
				return hr == MF_E_NO_MORE_TYPES ? MF_E_INVALIDMEDIATYPE : hr;
			}

			GUID subtype = GUID_NULL;

			if (FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) || !IsEqualGUID(subtype, MFVideoFormat_NV12)) {
				continue;
			}

			hr = transform->SetOutputType(outputStreamId, type.Get(), 0);

			if (FAILED(hr)) {
				return hr;
			}

			UINT32 frameWidth = 0;
			UINT32 frameHeight = 0;
			MFGetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, &frameWidth, &frameHeight);

			// The frame may be larger than the picture, e.g. 1088 rows for
			// 1080; the aperture says what is picture.
			MFVideoArea aperture = {};
			UINT32 size = 0;

			if (SUCCEEDED(type->GetBlob(MF_MT_MINIMUM_DISPLAY_APERTURE, reinterpret_cast<UINT8 *>(&aperture),
				sizeof(aperture), &size)) && size == sizeof(aperture) && aperture.Area.cx > 0 && aperture.Area.cy > 0)
			{
				width = static_cast<uint32_t>(aperture.Area.cx);
				height = static_cast<uint32_t>(aperture.Area.cy);
			}
			else {
				width = frameWidth;
				height = frameHeight;
			}

			return S_OK;
		}
	}

	int32_t MFVideoDecoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (!transform || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		ComPtr<IMFMediaBuffer> buffer;
		HRESULT hr = MFCreateMemoryBuffer(static_cast<DWORD>(image.size()), &buffer);

		BYTE * data = nullptr;

		if (SUCCEEDED(hr)) {
			hr = buffer->Lock(&data, nullptr, nullptr);
		}
		if (FAILED(hr)) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		std::memcpy(data, image.data(), image.size());

		buffer->Unlock();
		buffer->SetCurrentLength(static_cast<DWORD>(image.size()));

		ComPtr<IMFSample> sample;
		hr = MFCreateSample(&sample);

		if (SUCCEEDED(hr)) {
			hr = sample->AddBuffer(buffer.Get());
		}
		if (FAILED(hr)) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// The sample time identifies the frame when it comes out decoded.
		sampleTime += 10000;
		sample->SetSampleTime(sampleTime);

		if (image.frame_type() == webrtc::VideoFrameType::kVideoFrameKey) {
			sample->SetUINT32(MFSampleExtension_CleanPoint, TRUE);
		}

		pendingFrames[sampleTime] = PendingFrame {
			image.RtpTimestamp(),
			image.ntp_time_ms_,
			renderTimeMs,
			image.rotation_
		};

		hr = transform->ProcessInput(inputStreamId, sample.Get(), 0);

		if (hr == MF_E_NOTACCEPTING) {
			// Output that is still waiting has to be taken first.
			if (!DrainOutput()) {
				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			hr = transform->ProcessInput(inputStreamId, sample.Get(), 0);
		}

		if (FAILED(hr)) {
			RTC_LOG(LS_WARNING) << implementationName << " rejected a frame, hr=" << hr;
			pendingFrames.erase(sampleTime);
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		return DrainOutput() ? WEBRTC_VIDEO_CODEC_OK : WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
	}

	bool MFVideoDecoder::DrainOutput()
	{
		for (;;) {
			MFT_OUTPUT_STREAM_INFO info = {};

			if (FAILED(transform->GetOutputStreamInfo(outputStreamId, &info))) {
				return false;
			}

			ComPtr<IMFSample> ownSample;

			if (!(info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES))) {
				ComPtr<IMFMediaBuffer> buffer;

				if (FAILED(MFCreateSample(&ownSample)) || FAILED(MFCreateMemoryBuffer(info.cbSize, &buffer)) ||
					FAILED(ownSample->AddBuffer(buffer.Get())))
				{
					return false;
				}
			}

			MFT_OUTPUT_DATA_BUFFER output = {};
			output.dwStreamID = outputStreamId;
			output.pSample = ownSample.Get();

			DWORD status = 0;
			HRESULT hr = transform->ProcessOutput(0, 1, &output, &status);

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

			if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
				return true;
			}
			if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
				// The stream settled its size; the output type has to be set
				// again before frames come out.
				if (FAILED(SetOutputType())) {
					return false;
				}

				staging.Reset();
				continue;
			}
			if (FAILED(hr) || !sample) {
				RTC_LOG(LS_WARNING) << implementationName << " failed to produce output, hr=" << hr;
				return false;
			}

			if (!DeliverOutput(sample.Get())) {
				return false;
			}
		}
	}

	bool MFVideoDecoder::DeliverOutput(IMFSample * sample)
	{
		LONGLONG time = 0;
		sample->GetSampleTime(&time);

		auto found = pendingFrames.find(time);

		if (found == pendingFrames.end()) {
			RTC_LOG(LS_WARNING) << implementationName << " produced a frame for no input, time " << time;
			return true;
		}

		const PendingFrame pending = found->second;

		// Frames the decoder skipped come out never.
		pendingFrames.erase(pendingFrames.begin(), std::next(found));

		ComPtr<IMFMediaBuffer> buffer;

		if (FAILED(sample->GetBufferByIndex(0, &buffer))) {
			return false;
		}

		webrtc::scoped_refptr<webrtc::I420Buffer> i420;

		if (!ReadFrame(buffer.Get(), i420)) {
			return false;
		}

		webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(i420)
			.set_rtp_timestamp(pending.rtpTimestamp)
			.set_timestamp_ms(pending.renderTimeMs)
			.set_ntp_time_ms(pending.ntpTimeMs)
			.set_rotation(pending.rotation)
			.build();

		callback->Decoded(frame, std::nullopt, std::nullopt);

		return true;
	}

	bool MFVideoDecoder::ReadFrame(IMFMediaBuffer * buffer, webrtc::scoped_refptr<webrtc::I420Buffer> & frame)
	{
		ComPtr<IMFDXGIBuffer> dxgiBuffer;

		if (FAILED(buffer->QueryInterface(IID_PPV_ARGS(&dxgiBuffer)))) {
			// Decoded in system memory, that is in software.
			RTC_LOG(LS_WARNING) << implementationName << " does not decode on the GPU";
			return false;
		}

		ComPtr<ID3D11Texture2D> texture;
		UINT subresource = 0;

		if (FAILED(dxgiBuffer->GetResource(IID_PPV_ARGS(&texture))) ||
			FAILED(dxgiBuffer->GetSubresourceIndex(&subresource)))
		{
			return false;
		}

		D3D11_TEXTURE2D_DESC desc = {};
		texture->GetDesc(&desc);

		if (desc.Format != DXGI_FORMAT_NV12 || desc.Width < width || desc.Height < height) {
			return false;
		}

		if (!staging || stagingDesc.Width != desc.Width || stagingDesc.Height != desc.Height) {
			stagingDesc = desc;
			stagingDesc.ArraySize = 1;
			stagingDesc.MipLevels = 1;
			stagingDesc.Usage = D3D11_USAGE_STAGING;
			stagingDesc.BindFlags = 0;
			stagingDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
			stagingDesc.MiscFlags = 0;

			staging.Reset();

			if (FAILED(device->CreateTexture2D(&stagingDesc, nullptr, &staging))) {
				return false;
			}
		}

		context->CopySubresourceRegion(staging.Get(), 0, 0, 0, 0, texture.Get(), subresource, nullptr);

		D3D11_MAPPED_SUBRESOURCE mapped = {};

		if (FAILED(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) {
			return false;
		}

		const uint8_t * y = static_cast<const uint8_t *>(mapped.pData);
		// The chroma plane follows all rows of the texture, not only those of
		// the picture.
		const uint8_t * uv = y + static_cast<size_t>(mapped.RowPitch) * desc.Height;

		frame = webrtc::I420Buffer::Create(static_cast<int>(width), static_cast<int>(height));

		libyuv::NV12ToI420(y, static_cast<int>(mapped.RowPitch), uv, static_cast<int>(mapped.RowPitch),
			frame->MutableDataY(), frame->StrideY(), frame->MutableDataU(), frame->StrideU(),
			frame->MutableDataV(), frame->StrideV(), static_cast<int>(width), static_cast<int>(height));

		context->Unmap(staging.Get(), 0);

		return true;
	}

	int32_t MFVideoDecoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t MFVideoDecoder::Release()
	{
		if (transform) {
			transform->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
			transform->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
			transform.Reset();
		}
		if (activate) {
			activate->ShutdownObject();
			activate.Reset();
		}

		staging.Reset();
		deviceManager.Reset();
		context.Reset();
		device.Reset();
		mfInitializer.reset();

		pendingFrames.clear();

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo MFVideoDecoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * MFVideoDecoder::ImplementationName() const
	{
		return implementationName.c_str();
	}
}
