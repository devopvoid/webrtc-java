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

#include "api/ExtensionApi.h"

#include "media/audio/CustomAudioSource.h"
#include "media/video/CustomVideoSource.h"

#include "api/video/video_frame.h"
#include "api/video/video_rotation.h"
#include "common_video/include/video_frame_buffer.h"
#include "rtc_base/time_utils.h"

#include <cstddef>

namespace jni
{
	namespace
	{
		int64_t ApiNowUs()
		{
			return webrtc::TimeMicros();
		}

		bool ToVideoRotation(int degrees, webrtc::VideoRotation & rotation)
		{
			switch (degrees) {
				case 0:
					rotation = webrtc::kVideoRotation_0;
					return true;
				case 90:
					rotation = webrtc::kVideoRotation_90;
					return true;
				case 180:
					rotation = webrtc::kVideoRotation_180;
					return true;
				case 270:
					rotation = webrtc::kVideoRotation_270;
					return true;
				default:
					return false;
			}
		}

		// Reports the frame as consumed without delivering it. The contract in
		// webrtc_java_api.h is that the release callback runs whatever the
		// outcome, so a rejected frame must not leak the caller's buffer.
		int ReleaseAndFail(const wj_i420_frame * frame, int error)
		{
			if (frame != nullptr && frame->release != nullptr) {
				frame->release(frame->opaque);
			}

			return error;
		}

		int ApiVideoSourcePush(void * source, const wj_i420_frame * frame)
		{
			if (frame == nullptr) {
				return WEBRTC_JAVA_ERR_HANDLE;
			}
			if (source == nullptr || frame->y == nullptr || frame->u == nullptr || frame->v == nullptr) {
				return ReleaseAndFail(frame, WEBRTC_JAVA_ERR_HANDLE);
			}

			const int chromaWidth = (frame->width + 1) / 2;

			if (frame->width <= 0 || frame->height <= 0
					|| frame->stride_y < frame->width
					|| frame->stride_u < chromaWidth
					|| frame->stride_v < chromaWidth) {
				return ReleaseAndFail(frame, WEBRTC_JAVA_ERR_FORMAT);
			}

			webrtc::VideoRotation rotation;

			if (!ToVideoRotation(frame->rotation, rotation)) {
				return ReleaseAndFail(frame, WEBRTC_JAVA_ERR_FORMAT);
			}

			// The planes are wrapped, not copied. WebRTC keeps the buffer alive
			// for as long as it needs the pixels, which outlives this call, and
			// runs the callback when it drops the last reference.
			wj_release_fn release = frame->release;
			void * opaque = frame->opaque;

			webrtc::scoped_refptr<webrtc::I420BufferInterface> buffer = webrtc::WrapI420Buffer(
					frame->width, frame->height,
					frame->y, frame->stride_y,
					frame->u, frame->stride_u,
					frame->v, frame->stride_v,
					[release, opaque]() {
						if (release != nullptr) {
							release(opaque);
						}
					});

			webrtc::VideoFrame videoFrame = webrtc::VideoFrame::Builder()
					.set_video_frame_buffer(buffer)
					.set_rotation(rotation)
					.build();

			CustomVideoSource * videoSource = static_cast<CustomVideoSource *>(source);

			if (frame->timestamp_us != 0) {
				videoSource->PushFrame(videoFrame, frame->timestamp_us);
			}
			else {
				videoSource->PushFrame(videoFrame);
			}

			return WEBRTC_JAVA_OK;
		}

		int ApiAudioSourcePush(void * source, const wj_audio_chunk * chunk)
		{
			if (source == nullptr || chunk == nullptr || chunk->samples == nullptr) {
				return WEBRTC_JAVA_ERR_HANDLE;
			}
			if (chunk->sample_rate <= 0 || chunk->sample_rate > 48000
					|| chunk->channels < 1 || chunk->channels > 2
					|| chunk->frames <= 0 || chunk->frames * 100 != chunk->sample_rate) {
				return WEBRTC_JAVA_ERR_FORMAT;
			}

			CustomAudioSource * audioSource = static_cast<CustomAudioSource *>(source);

			if (chunk->timestamp_us != 0) {
				audioSource->PushAudioData(chunk->samples, 16, chunk->sample_rate,
						static_cast<size_t>(chunk->channels),
						static_cast<size_t>(chunk->frames),
						chunk->timestamp_us);
			}
			else {
				audioSource->PushAudioData(chunk->samples, 16, chunk->sample_rate,
						static_cast<size_t>(chunk->channels),
						static_cast<size_t>(chunk->frames));
			}

			return WEBRTC_JAVA_OK;
		}

		// The handles are the pointers the Java sources hold, typed as the
		// concrete class, which is why a video and an audio source each get
		// their own pair: a void pointer cannot be released without knowing
		// what it points to.
		void ApiVideoSourceRetain(void * source)
		{
			if (source != nullptr) {
				static_cast<CustomVideoSource *>(source)->AddRef();
			}
		}

		void ApiVideoSourceRelease(void * source)
		{
			if (source != nullptr) {
				static_cast<CustomVideoSource *>(source)->Release();
			}
		}

		void ApiAudioSourceRetain(void * source)
		{
			if (source != nullptr) {
				static_cast<CustomAudioSource *>(source)->AddRef();
			}
		}

		void ApiAudioSourceRelease(void * source)
		{
			if (source != nullptr) {
				static_cast<CustomAudioSource *>(source)->Release();
			}
		}

		const webrtc_java_api kExtensionApi = {
			WEBRTC_JAVA_API_VERSION,
			static_cast<uint32_t>(sizeof(webrtc_java_api)),
			&ApiNowUs,
			&ApiVideoSourcePush,
			&ApiAudioSourcePush,
			&ApiVideoSourceRetain,
			&ApiVideoSourceRelease,
			&ApiAudioSourceRetain,
			&ApiAudioSourceRelease
		};
	}

	const webrtc_java_api * GetExtensionApi()
	{
		return &kExtensionApi;
	}
}
