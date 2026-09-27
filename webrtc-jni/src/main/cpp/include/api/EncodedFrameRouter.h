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

#ifndef JNI_WEBRTC_API_ENCODED_FRAME_ROUTER_H_
#define JNI_WEBRTC_API_ENCODED_FRAME_ROUTER_H_

#include "webrtc_java_api.h"

#include "api/frame_transformer_interface.h"
#include "api/media_types.h"
#include "api/scoped_refptr.h"

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

namespace jni
{
	class RTCEncodedFrameTransformer;

	// Everything an EncodedFrameTransformer knows about where its frames go:
	// the WebRTC callbacks that take them back, the native observers that
	// watch them pass, and the Java transform that may change them.
	//
	// It lives apart from the transformer so that the transformer's worker
	// thread can keep it alive on its own. That thread is never joined, since
	// a Java transform may do whatever it likes, including close the peer
	// connection and with it release the transformer on a thread that would
	// then wait for the transform to return.
	//
	// All methods are thread-safe.
	class EncodedFrameRouter
	{
		public:
			// A native observer, as the extension API hands it out.
			struct Observer
			{
				wj_encoded_frame_fn fn;
				void * opaque;
			};

		public:
			EncodedFrameRouter(webrtc::MediaType mediaType, bool sender);
			~EncodedFrameRouter() = default;

			EncodedFrameRouter(const EncodedFrameRouter &) = delete;
			EncodedFrameRouter & operator=(const EncodedFrameRouter &) = delete;

			webrtc::MediaType GetMediaType() const;

			// A sender's frames are observed before the Java transform, a
			// receiver's after it, so that observers see what the codec made
			// or will get, not what an encryption transform turns it into.
			bool IsSender() const;

			void SetCallback(webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback);
			void SetSinkCallback(uint32_t ssrc, webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback);
			void RemoveCallback();
			void RemoveSinkCallback(uint32_t ssrc);

			void SetTransformer(std::shared_ptr<RTCEncodedFrameTransformer> transformer);
			std::shared_ptr<RTCEncodedFrameTransformer> GetTransformer() const;

			Observer * AddObserver(wj_encoded_frame_fn fn, void * opaque);
			// When this returns, the observer is not running and never runs
			// again.
			void RemoveObserver(Observer * observer);

			// Shows the frame to every observer.
			void Observe(const webrtc::TransformableFrameInterface & frame);

			// Hands the frame back to WebRTC, to the callback that registered
			// for its SSRC.
			void Forward(std::unique_ptr<webrtc::TransformableFrameInterface> frame);

		private:
			const webrtc::MediaType mediaType;
			const bool sender;

			mutable std::mutex callbackMutex;
			webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback;
			std::map<uint32_t, webrtc::scoped_refptr<webrtc::TransformedFrameCallback>> sinkCallbacks;

			mutable std::mutex transformerMutex;
			std::shared_ptr<RTCEncodedFrameTransformer> transformer;

			// Held while observers run, which is what lets RemoveObserver()
			// promise that its observer is done.
			std::mutex observerMutex;
			std::vector<std::unique_ptr<Observer>> observers;
			// Read without the lock, to keep the common case of no observers
			// free of it.
			std::atomic<size_t> observerCount;
	};
}

#endif
