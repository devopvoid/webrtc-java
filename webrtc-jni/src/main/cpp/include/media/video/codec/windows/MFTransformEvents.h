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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_TRANSFORM_EVENTS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_TRANSFORM_EVENTS_H_

#include <mfapi.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <wrl/client.h>

#include <atomic>
#include <mutex>

namespace jni
{
	// Receives the events of an asynchronous Media Foundation transform.
	class MFTransformEventListener
	{
		public:
			virtual ~MFTransformEventListener() = default;

			// Called on a Media Foundation work queue thread, one event at a
			// time.
			virtual void OnTransformEvent(MediaEventType type, HRESULT status) = 0;
	};

	// Pumps the events of an asynchronous Media Foundation transform, which
	// hardware encoders are: the transform asks for input and announces
	// output through events rather than accepting frames on demand.
	//
	// Media Foundation keeps a reference to this object while an event
	// request is pending, so it may outlive its listener. Stop() detaches
	// the listener; once it returns, the listener is not called again.
	class MFTransformEvents : public IMFAsyncCallback
	{
		public:
			// Starts pumping the events of the transform to the listener.
			static HRESULT Start(IMFMediaEventGenerator * generator, MFTransformEventListener * listener,
				Microsoft::WRL::ComPtr<MFTransformEvents> * events);

			// Detaches the listener, waiting for an event it is handling.
			void Stop();

			// IUnknown
			STDMETHODIMP QueryInterface(REFIID iid, void ** object) override;
			STDMETHODIMP_(ULONG) AddRef() override;
			STDMETHODIMP_(ULONG) Release() override;

			// IMFAsyncCallback
			STDMETHODIMP GetParameters(DWORD * flags, DWORD * queue) override;
			STDMETHODIMP Invoke(IMFAsyncResult * result) override;

		private:
			MFTransformEvents(IMFMediaEventGenerator * generator, MFTransformEventListener * listener);
			virtual ~MFTransformEvents() = default;

		private:
			std::atomic<ULONG> references;

			const Microsoft::WRL::ComPtr<IMFMediaEventGenerator> generator;

			// Guards the listener, and is held while it handles an event.
			std::mutex mutex;
			MFTransformEventListener * listener;
	};
}

#endif
