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

#include "api/EncodedFrameTransformer.h"
#include "api/RTCEncodedFrameTransformer.h"

#include <map>
#include <type_traits>

namespace jni
{
	namespace
	{
		using RegistryKey = std::pair<const void *, std::string>;
		using RegistryMap = std::map<RegistryKey, EncodedFrameTransformer *>;

		// Never destroyed: a transformer may be released while the process
		// shuts down, after static destructors have run.
		std::mutex & RegistryMutex()
		{
			static std::mutex * mutex = new std::mutex();
			return *mutex;
		}

		RegistryMap & Registry()
		{
			static RegistryMap * registry = new RegistryMap();
			return *registry;
		}
	}

	webrtc::scoped_refptr<EncodedFrameTransformer> EncodedFrameTransformer::Of(webrtc::RtpSenderInterface * sender)
	{
		return Lookup(sender, true);
	}

	webrtc::scoped_refptr<EncodedFrameTransformer> EncodedFrameTransformer::Of(webrtc::RtpReceiverInterface * receiver)
	{
		return Lookup(receiver, true);
	}

	webrtc::scoped_refptr<EncodedFrameTransformer> EncodedFrameTransformer::Find(webrtc::RtpSenderInterface * sender)
	{
		return Lookup(sender, false);
	}

	webrtc::scoped_refptr<EncodedFrameTransformer> EncodedFrameTransformer::Find(webrtc::RtpReceiverInterface * receiver)
	{
		return Lookup(receiver, false);
	}

	template <typename T>
	webrtc::scoped_refptr<EncodedFrameTransformer> EncodedFrameTransformer::Lookup(T * owner, bool install)
	{
		if (owner == nullptr) {
			return nullptr;
		}

		constexpr bool isSender = std::is_base_of_v<webrtc::RtpSenderInterface, T>;

		// Asked before taking the lock: the owner is a proxy, which runs this
		// on the signaling thread and waits for it.
		Key key(static_cast<const void *>(owner), owner->id());

		auto find = [&key]() -> EncodedFrameTransformer * {
			// Only a transformer that is not already on its way out counts;
			// one whose last reference is being dropped is as good as gone,
			// and its Release() leaves a replacement in the registry alone.
			auto found = Registry().find(key);

			if (found != Registry().end() && found->second->TryAddRef()) {
				return found->second;
			}

			return nullptr;
		};

		EncodedFrameTransformer * existing = nullptr;

		{
			std::lock_guard<std::mutex> lock(RegistryMutex());

			existing = find();
		}

		if (existing == nullptr && !install) {
			return nullptr;
		}

		webrtc::scoped_refptr<EncodedFrameTransformer> transformer;

		if (existing == nullptr) {
			transformer = webrtc::scoped_refptr<EncodedFrameTransformer>(
				new EncodedFrameTransformer(key, owner->media_type(), isSender));

			std::lock_guard<std::mutex> lock(RegistryMutex());

			// Another thread may have installed one in the meantime, and that
			// one wins; this one goes again as soon as it is let go of.
			existing = find();

			if (existing == nullptr) {
				Registry()[key] = transformer.get();
			}
		}

		if (existing != nullptr) {
			// TryAddRef() took the reference this pointer now carries.
			transformer = webrtc::scoped_refptr<EncodedFrameTransformer>(existing);
			existing->Release();

			return transformer;
		}

		owner->SetFrameTransformer(transformer);

		return transformer;
	}

	EncodedFrameTransformer::EncodedFrameTransformer(Key key, webrtc::MediaType mediaType, bool sender) :
		key(std::move(key)),
		router(std::make_shared<EncodedFrameRouter>(mediaType, sender)),
		refCount(0),
		worker(nullptr)
	{
	}

	EncodedFrameTransformer::~EncodedFrameTransformer()
	{
		// Stops the worker without waiting for it; the router it shares lives
		// on until the worker is done with it.
		delete worker.load(std::memory_order_acquire);
	}

	void EncodedFrameTransformer::SetTransformer(JNIEnv * env, jobject transformer)
	{
		std::shared_ptr<RTCEncodedFrameTransformer> javaTransformer;

		if (transformer != nullptr) {
			javaTransformer = std::make_shared<RTCEncodedFrameTransformer>(env, transformer);

			std::lock_guard<std::mutex> lock(workerMutex);

			if (worker.load(std::memory_order_acquire) == nullptr) {
				JavaVM * vm = nullptr;

				if (env->GetJavaVM(&vm) == JNI_OK) {
					worker.store(new EncodedFrameWorker(vm, router), std::memory_order_release);
				}
			}
		}

		router->SetTransformer(std::move(javaTransformer));
	}

	EncodedFrameRouter::Observer * EncodedFrameTransformer::AddObserver(wj_encoded_frame_fn fn, void * opaque)
	{
		return router->AddObserver(fn, opaque);
	}

	void EncodedFrameTransformer::RemoveObserver(EncodedFrameRouter::Observer * observer)
	{
		router->RemoveObserver(observer);
	}

	void EncodedFrameTransformer::Transform(std::unique_ptr<webrtc::TransformableFrameInterface> frame)
	{
		if (!frame) {
			return;
		}

		if (router->IsSender()) {
			router->Observe(*frame);
		}

		EncodedFrameWorker * frameWorker = worker.load(std::memory_order_acquire);

		if (frameWorker != nullptr) {
			frameWorker->Post(std::move(frame));
			return;
		}

		if (!router->IsSender()) {
			router->Observe(*frame);
		}

		router->Forward(std::move(frame));
	}

	void EncodedFrameTransformer::RegisterTransformedFrameCallback(webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback)
	{
		router->SetCallback(std::move(callback));
	}

	void EncodedFrameTransformer::RegisterTransformedFrameSinkCallback(webrtc::scoped_refptr<webrtc::TransformedFrameCallback> callback, uint32_t ssrc)
	{
		router->SetSinkCallback(ssrc, std::move(callback));
	}

	void EncodedFrameTransformer::UnregisterTransformedFrameCallback()
	{
		router->RemoveCallback();
	}

	void EncodedFrameTransformer::UnregisterTransformedFrameSinkCallback(uint32_t ssrc)
	{
		router->RemoveSinkCallback(ssrc);
	}

	void EncodedFrameTransformer::AddRef() const
	{
		refCount.fetch_add(1, std::memory_order_relaxed);
	}

	webrtc::RefCountReleaseStatus EncodedFrameTransformer::Release() const
	{
		if (refCount.fetch_sub(1, std::memory_order_acq_rel) != 1) {
			return webrtc::RefCountReleaseStatus::kOtherRefsRemained;
		}

		{
			std::lock_guard<std::mutex> lock(RegistryMutex());

			auto found = Registry().find(key);

			// A lookup that found this transformer dying may have put a new
			// one in its place, which stays.
			if (found != Registry().end() && found->second == this) {
				Registry().erase(found);
			}
		}

		delete this;

		return webrtc::RefCountReleaseStatus::kDroppedLastRef;
	}

	bool EncodedFrameTransformer::TryAddRef() const
	{
		int count = refCount.load(std::memory_order_relaxed);

		while (count > 0) {
			if (refCount.compare_exchange_weak(count, count + 1, std::memory_order_acq_rel,
					std::memory_order_relaxed)) {
				return true;
			}
		}

		return false;
	}
}
