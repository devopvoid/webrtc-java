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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_CAPTURE_TAP_H_
#define JNI_WEBRTC_MEDIA_AUDIO_CAPTURE_TAP_H_

#include "api/audio/audio_processing.h"
#include "api/AudioTrackSink.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace jni
{
	// Hands the capture signal of an AudioProcessing, after all of its
	// processing, to a sink: what the senders send. The module calls it
	// through its capture post-processor, which is put in when the module is
	// built, so the tap does nothing until a sink is set.
	//
	// The tap is shared: by the post-processor, for as long as the module
	// lives, and by the Java objects that set its sink, the module's and the
	// factory's, for as long as they live. None of them outlives it.
	class AudioCaptureTap
	{
		public:
			// The capture post-processor to build an AudioProcessing with.
			static std::unique_ptr<webrtc::CustomProcessing> CreateProcessing(std::shared_ptr<AudioCaptureTap> tap);

			// Replaces the sink, or stops the delivery with nullptr. Once this
			// returns, the sink replaced is no longer called. Must not be
			// called from within the sink.
			void SetSink(std::unique_ptr<AudioTrackSink> sink);

			// Called on the capture thread.
			void Process(const webrtc::AudioBuffer & audio);

		private:
			std::mutex mutex;

			// Guarded by mutex.
			std::unique_ptr<AudioTrackSink> sink;

			// Guarded by mutex.
			std::vector<int16_t> samples;
	};
}

#endif
