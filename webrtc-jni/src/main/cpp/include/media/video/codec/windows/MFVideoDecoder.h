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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_VIDEO_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_VIDEO_DECODER_H_

#include "platform/windows/MFInitializer.h"

#include "api/video/encoded_image.h"
#include "api/video/i420_buffer.h"
#include "api/video/video_codec_type.h"
#include "api/video/video_rotation.h"
#include "api/video_codecs/video_decoder.h"

#include <d3d11.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <wrl/client.h>

#include <cstdint>
#include <map>
#include <memory>
#include <string>

namespace jni
{
	// Decodes H.264, AV1 or VP9 on the GPU, with the decoder transform of Windows
	// for the codec, which decodes through DXVA on the Direct3D 11 device it
	// is given.
	//
	// Decoding is synchronous, and in low-latency mode each frame comes out
	// of the transform as soon as it went in. Decoded frames are in GPU
	// memory; each one is copied to system memory and converted to I420,
	// which is what WebRTC's frames hold. A transform whose output is not in
	// GPU memory decodes in software, which WebRTC's own decoders do better,
	// so the decoder gives up then, as it does when anything fails, and the
	// software decoder takes over.
	class MFVideoDecoder : public webrtc::VideoDecoder
	{
		public:
			// The codec is H.264, AV1 or VP9.
			explicit MFVideoDecoder(webrtc::VideoCodecType codec);
			~MFVideoDecoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// What becomes of an encoded frame once it is decoded.
			struct PendingFrame
			{
				uint32_t rtpTimestamp;
				int64_t ntpTimeMs;
				int64_t renderTimeMs;
				webrtc::VideoRotation rotation;
			};

			HRESULT CreateTransform(const Settings & settings);
			HRESULT SetOutputType();
			bool DrainOutput();
			bool DeliverOutput(IMFSample * sample);
			bool ReadFrame(IMFMediaBuffer * buffer, webrtc::scoped_refptr<webrtc::I420Buffer> & frame);

		private:
			const webrtc::VideoCodecType codec;
			std::string implementationName;

			std::unique_ptr<MFInitializer> mfInitializer;

			Microsoft::WRL::ComPtr<ID3D11Device> device;
			Microsoft::WRL::ComPtr<ID3D11DeviceContext> context;
			Microsoft::WRL::ComPtr<IMFDXGIDeviceManager> deviceManager;
			Microsoft::WRL::ComPtr<IMFActivate> activate;
			Microsoft::WRL::ComPtr<IMFTransform> transform;
			DWORD inputStreamId;
			DWORD outputStreamId;

			// The texture decoded frames are copied into, to read them.
			Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
			D3D11_TEXTURE2D_DESC stagingDesc;

			// The size of the picture, within the possibly larger frame.
			uint32_t width;
			uint32_t height;

			webrtc::DecodedImageCallback * callback;

			LONGLONG sampleTime;
			std::map<LONGLONG, PendingFrame> pendingFrames;
	};
}

#endif
