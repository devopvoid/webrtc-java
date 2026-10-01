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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_H264_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_H264_ENCODER_H_

#include "media/video/codec/windows/MFTransformEvents.h"
#include "platform/windows/MFInitializer.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"
#include "common_video/h264/h264_bitstream_parser.h"
#include "modules/video_coding/codecs/h264/include/h264_globals.h"

#include <mftransform.h>
#include <strmif.h>
#include <wrl/client.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace jni
{
	// Encodes H.264 with the hardware encoder of the GPU, through the Media
	// Foundation transform its driver provides. These transforms are
	// asynchronous: they ask for input and announce output through events,
	// which arrive on a Media Foundation thread, so encoded frames are
	// handed to WebRTC from there.
	//
	// Frames are passed in system memory as NV12; the transform uploads them.
	// Anything that fails makes the encoder give up, so that WebRTC switches
	// to the software encoder.
	class MFH264Encoder : public webrtc::VideoEncoder, public MFTransformEventListener
	{
		public:
			explicit MFH264Encoder(const webrtc::SdpVideoFormat & format);
			~MFH264Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

			void OnTransformEvent(MediaEventType type, HRESULT status) override;

		private:
			// What becomes of an input frame once its output arrives.
			struct PendingFrame
			{
				uint32_t rtpTimestamp;
				int64_t captureTimeMs;
				int64_t ntpTimeMs;
				webrtc::VideoRotation rotation;
			};

			HRESULT CreateTransform();
			HRESULT ConfigureTypes();
			void ConfigureCodec();
			void SetBitrate(uint32_t bitrateBps);
			HRESULT CreateInputSample(const webrtc::VideoFrame & frame, IMFSample ** sample);
			void ProcessOutput();
			void DeliverOutput(IMFSample * sample);
			void ShutdownTransform();

		private:
			const webrtc::SdpVideoFormat format;
			std::string implementationName;

			std::unique_ptr<MFInitializer> mfInitializer;

			// Activated anew for each initialization, since an activation
			// object cannot be relied on to activate again once shut down.
			Microsoft::WRL::ComPtr<IMFActivate> activate;
			Microsoft::WRL::ComPtr<IMFTransform> transform;
			Microsoft::WRL::ComPtr<ICodecAPI> codecApi;
			Microsoft::WRL::ComPtr<MFTransformEvents> events;
			DWORD inputStreamId;
			DWORD outputStreamId;

			webrtc::VideoCodec codecSettings;
			uint32_t bitrateBps;
			uint32_t framerate;

			std::atomic<webrtc::EncodedImageCallback *> callback;

			// Set once the transform fails, from any thread.
			std::atomic<bool> failed;

			// Input the transform asked for and has not been given.
			std::mutex inputMutex;
			std::condition_variable inputRequested;
			int inputRequests;

			// Frames given to the transform, by sample time. Used on the
			// encoder thread and the event thread.
			std::mutex pendingMutex;
			std::map<LONGLONG, PendingFrame> pendingFrames;

			// Used on the encoder thread only.
			LONGLONG lastSampleTime;
			bool keyFrameRequested;

			// Used on the event thread only.
			std::vector<uint8_t> parameterSets;
			webrtc::H264BitstreamParser bitstreamParser;
			webrtc::H264PacketizationMode packetizationMode;
	};
}

#endif
