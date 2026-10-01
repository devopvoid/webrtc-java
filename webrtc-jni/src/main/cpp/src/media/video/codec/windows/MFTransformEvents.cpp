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

#include "media/video/codec/windows/MFTransformEvents.h"

using Microsoft::WRL::ComPtr;

namespace jni
{
	HRESULT MFTransformEvents::Start(IMFMediaEventGenerator * generator, MFTransformEventListener * listener,
		ComPtr<MFTransformEvents> * events)
	{
		ComPtr<MFTransformEvents> instance;
		instance.Attach(new MFTransformEvents(generator, listener));

		HRESULT hr = generator->BeginGetEvent(instance.Get(), nullptr);

		if (SUCCEEDED(hr)) {
			*events = instance;
		}

		return hr;
	}

	MFTransformEvents::MFTransformEvents(IMFMediaEventGenerator * generator, MFTransformEventListener * listener) :
		references(1),
		generator(generator),
		listener(listener)
	{
	}

	void MFTransformEvents::Stop()
	{
		std::lock_guard<std::mutex> lock(mutex);

		listener = nullptr;
	}

	STDMETHODIMP MFTransformEvents::QueryInterface(REFIID iid, void ** object)
	{
		if (object == nullptr) {
			return E_POINTER;
		}

		if (iid == __uuidof(IUnknown) || iid == __uuidof(IMFAsyncCallback)) {
			*object = static_cast<IMFAsyncCallback *>(this);
			AddRef();

			return S_OK;
		}

		*object = nullptr;

		return E_NOINTERFACE;
	}

	STDMETHODIMP_(ULONG) MFTransformEvents::AddRef()
	{
		return ++references;
	}

	STDMETHODIMP_(ULONG) MFTransformEvents::Release()
	{
		ULONG count = --references;

		if (count == 0) {
			delete this;
		}

		return count;
	}

	STDMETHODIMP MFTransformEvents::GetParameters(DWORD * flags, DWORD * queue)
	{
		// The default work queue.
		return E_NOTIMPL;
	}

	STDMETHODIMP MFTransformEvents::Invoke(IMFAsyncResult * result)
	{
		ComPtr<IMFMediaEvent> event;

		// Fails with MF_E_SHUTDOWN once the transform is shut down, which
		// ends the pump.
		HRESULT hr = generator->EndGetEvent(result, &event);

		if (FAILED(hr)) {
			return S_OK;
		}

		MediaEventType type = MEUnknown;
		HRESULT status = S_OK;

		event->GetType(&type);
		event->GetStatus(&status);

		std::lock_guard<std::mutex> lock(mutex);

		if (listener == nullptr) {
			return S_OK;
		}

		listener->OnTransformEvent(type, status);

		// Asks for the next event only while someone listens.
		hr = generator->BeginGetEvent(this, nullptr);

		if (FAILED(hr)) {
			listener->OnTransformEvent(MEError, hr);
		}

		return S_OK;
	}
}
