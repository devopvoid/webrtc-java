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

#ifndef JNI_WEBRTC_API_ENCODED_FRAME_TRANSFORMER_H_
#define JNI_WEBRTC_API_ENCODED_FRAME_TRANSFORMER_H_

#include "api/EncodedFrameRouter.h"
#include "api/EncodedFrameWorker.h"

#include "api/frame_transformer_interface.h"
#include "api/rtp_receiver_interface.h"
#include "api/rtp_sender_interface.h"
#include "api/scoped_refptr.h"

#include <jni.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <utility>

namespace jni
{
	// The frame transformer this library installs on an RtpSender or
	// RtpReceiver, through which both a Java transform and native observers
	// (e.g. a recorder) see its encoded frames.
	//
	// A sender or receiver has room for one transformer, and there are good
	// reasons to install it only once: replacing the transformer of a video
	// sender recreates its send stream, which costs a key frame. So there is
	// exactly one of these per sender or receiver, installed the first time
	// anything asks for it and kept for the rest of its life; with nothing to
	// do, it hands frames straight back on the thread they came on.
	//
	// Many Java objects may stand for the same native sender or receiver, so
	// the transformer is found through a process-wide registry, not through
	// the Java object. The registry holds no reference: a transformer leaves
	// it when its sender or receiver, and everything else, lets it go.
	class EncodedFrameTransformer : public webrtc::FrameTransformerInterface
	{
		public:
			// Returns the transformer of the sender, installing one first if
			// it has none.
			static webrtc::scoped_refptr<EncodedFrameTransformer> Of(webrtc::RtpSenderInterface * sender);
			// Returns the transformer of the receiver, installing one first if
			// it has none.
			static webrtc::scoped_refptr<EncodedFrameTransformer> Of(webrtc::RtpReceiverInterface * receiver);

			// Returns the transformer of the sender or receiver if one was
			// installed, and null otherwise.
			static webrtc::scoped_refptr<EncodedFrameTransformer> Find(webrtc::RtpSenderInterface * sender);
			static webrtc::scoped_refptr<EncodedFrameTransformer> Find(webrtc::RtpReceiverInterface * receiver);

			// Sets the Java transform, or clears it with null. The first
			// transform starts the worker it runs on, and from then on every
			// frame passes through that worker, transform or not, so that
			// frames never overtake each other when the transform changes.
			void SetTransformer(JNIEnv * env, jobject transformer);

			EncodedFrameRouter::Observer * AddObserver(wj_encoded_frame_fn fn, void * opaque);
			void RemoveObserver(EncodedFrameRouter::Observer * observer);

			// FrameTransformerInterface implementation.
			void Transform(std::unique_ptr<webrtc::TransformableFrameInterface> frame) override;
			void RegisterTransformedFrameCallback(webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback) override;
			void RegisterTransformedFrameSinkCallback(webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback, uint32_t ssrc) override;
			void UnregisterTransformedFrameCallback() override;
			void UnregisterTransformedFrameSinkCallback(uint32_t ssrc) override;

			// RefCountInterface implementation, done by hand so that the
			// registry can tell a live transformer from a dying one.
			void AddRef() const override;
			webrtc::RefCountReleaseStatus Release() const override;

		protected:
			~EncodedFrameTransformer() override;

		private:
			// A sender or receiver is known by its address and its ID, so that
			// a new one allocated where an old one was is not mistaken for it
			// while the old one's transformer is still being torn down.
			using Key = std::pair<const void *, std::string>;

			EncodedFrameTransformer(Key key, webrtc::MediaType mediaType, bool sender);

			template <typename T>
			static webrtc::scoped_refptr<EncodedFrameTransformer> Lookup(T * owner, bool install);

			bool TryAddRef() const;

		private:
			const Key key;
			const std::shared_ptr<EncodedFrameRouter> router;

			mutable std::atomic<int> refCount;

			// Set once, by the first Java transform, and only read after.
			std::mutex workerMutex;
			std::atomic<EncodedFrameWorker *> worker;
	};
}

#endif
