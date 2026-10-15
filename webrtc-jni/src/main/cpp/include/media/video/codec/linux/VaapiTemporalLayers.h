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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_TEMPORAL_LAYERS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_TEMPORAL_LAYERS_H_

#include "media/video/codec/linux/VaapiEncodeSession.h"
#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/video_bitrate_allocation.h"
#include "api/video_codecs/scalability_mode.h"
#include "api/video_codecs/video_encoder.h"
#include "modules/video_coding/svc/scalability_mode_util.h"
#include "modules/video_coding/svc/scalable_video_controller.h"

#include <array>
#include <cstdint>
#include <optional>
#include <vector>

namespace jni
{
	// The temporal layers of a VA-API encoder of VP9 or AV1, none or those of
	// L1T2 and L1T3: which reference slot each frame refers to and which ones
	// it replaces, as WebRTC's structure of the mode says, each buffer of the
	// structure being the slot of the same number; which reconstructed surface
	// each slot holds; and the rate control of each layer, without which
	// AMD's driver gives the upper layers next to no bits, where the driver
	// says it has one and the encoder wants it. Intel's H.264 and H.265
	// encoders say not, and refuse the layer structure that goes with it;
	// their rate control treats every layer alike.
	class VaapiTemporalLayers
	{
		public:
			// The reconstructed surfaces a session needs: those of the two
			// reference buffers WebRTC's structures use up to L1T3, and that
			// of the frame being encoded.
			static constexpr int kReconstructedSurfaces = 3;

			// What a frame refers to and replaces.
			struct Frame
			{
				int temporalId = 0;
				// The slots of its references, the first being its last frame,
				// the second, if any, its golden frame.
				std::vector<int> references;
				// The slots it replaces, all for a key frame, and of those the
				// ones WebRTC's structure goes on to use: a key frame leaves
				// the others empty, which nothing refers to then, and which a
				// codec would otherwise keep as references for good.
				uint8_t refreshFlags = 0;
				uint8_t keptFlags = 0;
				// The reconstructed surface it is encoded into.
				int surface = -1;
			};

			VaapiTemporalLayers();

			// Whether the driver's encoder of the profile has a rate control
			// of each temporal layer, for as many layers as given.
			static bool LayerRateControl(VaapiLibrary & library, VAProfile profile, VAEntrypoint entrypoint,
				int layers);

			// Starts over in the mode, splitting the bitrate across the layers
			// until WebRTC gives its own split, for a rate control of each layer
			// where there is one. Returns false for a mode other than L1T1, L1T2
			// and L1T3.
			bool Configure(webrtc::ScalabilityMode mode, uint32_t bitrateBps, bool layerRateControl);

			// The number of temporal layers, one without layers.
			int Count() const;

			// Plans a frame of the layer WebRTC's structure gives. Returns
			// nothing if it refers to a slot that holds no frame, or to one
			// beyond those kept, or no surface is free.
			std::optional<Frame> Plan(bool keyFrame,
				const webrtc::ScalableVideoController::LayerFrameConfig & layerFrame) const;
			// Takes the frame, once encoded, into the slots it replaces.
			void Commit(const Frame & frame);
			// Empties a slot whose picture the decoder no longer keeps.
			void Forget(int slot);

			// The slots kept, and the reconstructed surface each holds, -1 for
			// none. A key frame fills the slots beyond too, but no frame refers
			// to them.
			int SlotCount() const;
			int Surface(int slot) const;

			// Takes the bitrate WebRTC gives each layer.
			void OnRatesUpdated(const webrtc::VideoBitrateAllocation & bitrates);
			// Adds the rate control of the stream, or of each layer, with the
			// layer structure along with that of a key frame. No initial
			// quantizer: the rate control picks its own, as FFmpeg leaves it.
			bool AddRateControl(VaapiEncodeSession & session, uint32_t bitrateBps, uint32_t framerate,
				bool keyFrame) const;
			// Fills in the share of the frame rate up to each layer.
			void FillFpsAllocation(webrtc::VideoEncoder::EncoderInfo & info) const;

		private:
			int count;
			bool layerRateControl;
			std::array<int, 2> slots;
			// The bitrate of each layer, counting the layers below it.
			std::vector<uint32_t> bitratesBps;
	};
}

#endif
