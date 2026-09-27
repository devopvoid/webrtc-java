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

#include "api/EncodedFrameRouter.h"
#include "api/RTCEncodedFrameTransformer.h"

#include "rtc_base/time_utils.h"

#include <algorithm>
#include <string>
#include <variant>

namespace jni
{
	EncodedFrameRouter::EncodedFrameRouter(webrtc::MediaType mediaType, bool sender) :
		mediaType(mediaType),
		sender(sender),
		observerCount(0)
	{
	}

	webrtc::MediaType EncodedFrameRouter::GetMediaType() const
	{
		return mediaType;
	}

	bool EncodedFrameRouter::IsSender() const
	{
		return sender;
	}

	void EncodedFrameRouter::SetCallback(webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback)
	{
		std::lock_guard<std::mutex> lock(callbackMutex);

		this->callback = std::move(callback);
	}

	void EncodedFrameRouter::SetSinkCallback(uint32_t ssrc, webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback)
	{
		std::lock_guard<std::mutex> lock(callbackMutex);

		sinkCallbacks[ssrc] = std::move(callback);
	}

	void EncodedFrameRouter::RemoveCallback()
	{
		webrtc::scoped_refptr<webrtc::TransformedFrameCallback> removed;

		{
			std::lock_guard<std::mutex> lock(callbackMutex);

			removed = std::move(callback);
		}

		// Released outside the lock: the last reference may take WebRTC state
		// down with it, which is none of this lock's business.
	}

	void EncodedFrameRouter::RemoveSinkCallback(uint32_t ssrc)
	{
		webrtc::scoped_refptr<webrtc::TransformedFrameCallback> removed;

		{
			std::lock_guard<std::mutex> lock(callbackMutex);

			auto found = sinkCallbacks.find(ssrc);

			if (found != sinkCallbacks.end()) {
				removed = std::move(found->second);
				sinkCallbacks.erase(found);
			}
		}
	}

	void EncodedFrameRouter::SetTransformer(std::shared_ptr<RTCEncodedFrameTransformer> transformer)
	{
		std::shared_ptr<RTCEncodedFrameTransformer> previous;

		{
			std::lock_guard<std::mutex> lock(transformerMutex);

			previous = std::move(this->transformer);
			this->transformer = std::move(transformer);
		}

		// The previous transform holds a global reference, which is deleted
		// here, outside the lock, or on the worker once it finishes a frame
		// it is still running.
	}

	std::shared_ptr<RTCEncodedFrameTransformer> EncodedFrameRouter::GetTransformer() const
	{
		std::lock_guard<std::mutex> lock(transformerMutex);

		return transformer;
	}

	EncodedFrameRouter::Observer * EncodedFrameRouter::AddObserver(wj_encoded_frame_fn fn, void * opaque)
	{
		auto observer = std::make_unique<Observer>(Observer{ fn, opaque });
		Observer * handle = observer.get();

		std::lock_guard<std::mutex> lock(observerMutex);

		observers.push_back(std::move(observer));
		observerCount.store(observers.size(), std::memory_order_release);

		return handle;
	}

	void EncodedFrameRouter::RemoveObserver(Observer * observer)
	{
		std::lock_guard<std::mutex> lock(observerMutex);

		auto found = std::find_if(observers.begin(), observers.end(),
			[observer](const std::unique_ptr<Observer> & o) { return o.get() == observer; });

		if (found != observers.end()) {
			observers.erase(found);
		}

		observerCount.store(observers.size(), std::memory_order_release);
	}

	void EncodedFrameRouter::Observe(const webrtc::TransformableFrameInterface & frame)
	{
		if (observerCount.load(std::memory_order_acquire) == 0) {
			return;
		}

		std::lock_guard<std::mutex> lock(observerMutex);

		if (observers.empty()) {
			return;
		}

		const std::span<const uint8_t> data = frame.GetData();
		const std::string mimeType = frame.GetMimeType();

		wj_encoded_frame encoded = {};
		encoded.data = data.data();
		encoded.size = data.size();
		encoded.mime_type = mimeType.c_str();
		encoded.payload_type = frame.GetPayloadType();
		encoded.ssrc = frame.GetSsrc();
		encoded.rtp_timestamp = std::visit([](auto timestamp) { return timestamp.value; },
			frame.GetRtpTimestampInfo());
		encoded.time_us = webrtc::TimeMicros();

		if (mediaType == webrtc::MediaType::VIDEO) {
			// The frames of a video sender or receiver are always video
			// frames, which is what makes this cast safe.
			const auto & videoFrame = static_cast<const webrtc::TransformableVideoFrameInterface &>(frame);
			const webrtc::VideoFrameMetadata metadata = videoFrame.Metadata();

			encoded.media_type = WEBRTC_JAVA_MEDIA_VIDEO;
			encoded.key_frame = videoFrame.IsKeyFrame() ? 1 : 0;
			encoded.width = metadata.GetWidth();
			encoded.height = metadata.GetHeight();
		}
		else {
			encoded.media_type = WEBRTC_JAVA_MEDIA_AUDIO;
		}

		for (const auto & observer : observers) {
			observer->fn(observer->opaque, &encoded);
		}
	}

	void EncodedFrameRouter::Forward(std::unique_ptr<webrtc::TransformableFrameInterface> frame)
	{
		webrtc::scoped_refptr<webrtc::TransformedFrameCallback> target;

		{
			std::lock_guard<std::mutex> lock(callbackMutex);

			auto found = sinkCallbacks.find(frame->GetSsrc());

			if (found != sinkCallbacks.end()) {
				target = found->second;
			}
			else if (callback) {
				target = callback;
			}
			else if (sinkCallbacks.size() == 1) {
				// A receiver of an unsignaled stream registers before it
				// knows the SSRC it will get.
				target = sinkCallbacks.begin()->second;
			}
		}

		// Called outside the lock, since WebRTC may unregister from within.
		// A frame with nowhere to go belongs to a stream that is gone, and is
		// simply released.
		if (target) {
			target->OnTransformedFrame(std::move(frame));
		}
	}
}
