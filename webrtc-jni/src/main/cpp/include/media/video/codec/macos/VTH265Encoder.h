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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_H265_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_H265_ENCODER_H_

#include "media/video/codec/EncoderOutputProcessor.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"

#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <VideoToolbox/VideoToolbox.h>

#include <atomic>
#include <cstdint>
#include <vector>

namespace jni
{
	// Encodes H.265 Main profile on the media engine of a Mac, through
	// VideoToolbox, and only there: the compression session is required to
	// be a hardware one, there being no software encoder for H.265.
	//
	// The stream is made for real-time use: no frame reordering, a key frame
	// only when WebRTC asks for one, a single layer. VideoToolbox hands out
	// the frames on a thread of its own, with length prefixed NAL units and
	// the parameter sets in the format description; they are turned into
	// Annex B there, with the parameter sets in front of each key frame, and
	// passed to WebRTC.
	//
	// Frames that come as a CVPixelBuffer, as from the camera, go to
	// VideoToolbox as they are; others are converted to NV12 first.
	class VTH265Encoder : public webrtc::VideoEncoder
	{
		public:
			explicit VTH265Encoder(const webrtc::SdpVideoFormat & format);
			~VTH265Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			// What becomes of an input frame once its output arrives.
			struct PendingFrame
			{
				VTH265Encoder * encoder;
				uint32_t rtpTimestamp;
				int64_t captureTimeMs;
				int64_t ntpTimeMs;
				webrtc::VideoRotation rotation;
			};

			static void OnOutput(void * encoder, void * frame, OSStatus status, VTEncodeInfoFlags flags,
				CMSampleBufferRef sample);

			bool CreateSession();
			void DestroySession();
			void ConfigureRates();

			// Returns a pixel buffer of the frame for the session, or null.
			CVPixelBufferRef CreatePixelBuffer(const webrtc::VideoFrame & frame);

			void DeliverOutput(const PendingFrame & pending, CMSampleBufferRef sample);

		private:
			webrtc::VideoCodec codecSettings;
			uint32_t bitrateBps;
			uint32_t framerate;

			VTCompressionSessionRef session;

			std::atomic<webrtc::EncodedImageCallback *> callback;

			// Set once the session fails, from any thread.
			std::atomic<bool> failed;

			// Used on the encoder thread only.
			int64_t lastTimestampUs;
			std::vector<uint8_t> scaleBuffer;

			// Used on the output thread of the session only.
			EncoderOutputProcessor outputProcessor;
			std::vector<uint8_t> annexB;
	};
}

#endif
