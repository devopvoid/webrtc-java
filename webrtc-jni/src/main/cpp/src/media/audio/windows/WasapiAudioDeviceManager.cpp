/*
 * Copyright 2019 Alex Andres
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

#include "media/audio/windows/WasapiAudioDeviceManager.h"
#include "platform/windows/WinUtils.h"

#include "functiondiscoverykeys_devpkey.h"

#include "rtc_base/logging.h"

namespace jni
{
	namespace avdev
	{
		WasapiAudioDeviceManager::WasapiAudioDeviceManager() :
			deviceEnumerator()
		{
			ComInitializer initializer;

			HRESULT hr;
			hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)&deviceEnumerator);
			THROW_IF_FAILED(hr, "WASAPI: Create device enumerator failed");

			deviceEnumerator->RegisterEndpointNotificationCallback(this);

			getAudioCaptureDevices();
			getAudioPlaybackDevices();
		}

		WasapiAudioDeviceManager::~WasapiAudioDeviceManager()
		{
			if (deviceEnumerator) {
				deviceEnumerator->UnregisterEndpointNotificationCallback(this);
			}
		}

		std::set<AudioDevicePtr> WasapiAudioDeviceManager::getAudioCaptureDevices()
		{
			if (captureDevices.empty()) {
				enumerateDevices(eCapture);
			}

			return captureDevices.devices();
		}

		std::set<AudioDevicePtr> WasapiAudioDeviceManager::getAudioPlaybackDevices()
		{
			if (playbackDevices.empty()) {
				enumerateDevices(eRender);
			}

			return playbackDevices.devices();
		}

		void WasapiAudioDeviceManager::enumerateDevices(EDataFlow dataFlow)
		{
			ComInitializer initializer;
			ComPtr<IMMDeviceCollection> deviceCollection;
			ComPtr<IMMDevice> defaultDevice;
			LPWSTR defaultDeviceId = nullptr;
			UINT count;
			HRESULT hr;

			hr = deviceEnumerator->EnumAudioEndpoints(dataFlow, DEVICE_STATE_ACTIVE, &deviceCollection);
			THROW_IF_FAILED(hr, "WASAPI: Enumerate audio endpoints failed");

			hr = deviceCollection->GetCount(&count);
			THROW_IF_FAILED(hr, "WASAPI: Get device count failed");

			hr = deviceEnumerator->GetDefaultAudioEndpoint(dataFlow, eMultimedia, &defaultDevice);
			if (SUCCEEDED(hr)) {
				hr = defaultDevice->GetId(&defaultDeviceId);
				THROW_IF_FAILED(hr, "WASAPI: Get default device id failed");
			}

			for (UINT i = 0; i < count; i++) {
				LPWSTR deviceId = nullptr;
				ComPtr<IMMDevice> pDevice;

				hr = deviceCollection->Item(i, &pDevice);
				if (FAILED(hr)) {
					RTC_LOG(LS_WARNING) << "WASAPI: DeviceCollection get device failed";
					continue;
				}

				hr = pDevice->GetId(&deviceId);
				if (FAILED(hr)) {
					RTC_LOG(LS_WARNING) << "WASAPI: Device get id failed";
					continue;
				}

				AudioDevicePtr device = createAudioDevice(deviceId, nullptr);

				if (device != nullptr) {
					bool isDefault = (defaultDeviceId != nullptr && wcscmp(defaultDeviceId, deviceId) == 0);

					if (dataFlow == eCapture && isDefault) {
						setDefaultCaptureDevice(device);
					}
					else if (dataFlow == eRender && isDefault) {
						setDefaultPlaybackDevice(device);
					}
				}

				CoTaskMemFree(deviceId);
			}

			CoTaskMemFree(defaultDeviceId);
		}

		void WasapiAudioDeviceManager::addDevice(LPCWSTR deviceId)
		{
			if (deviceId == nullptr) {
				return;
			}

			EDataFlow dataFlow;
			std::shared_ptr<AudioDevice> device = createAudioDevice(deviceId, &dataFlow);

			notifyDeviceConnected(device);
		}

		void WasapiAudioDeviceManager::removeDevice(LPCWSTR deviceId)
		{
			if (deviceId == nullptr)
				return;

			std::string id = WideStrToStr(deviceId);

			removeAudioDevice(captureDevices, id);
			removeAudioDevice(playbackDevices, id);
		}

		std::shared_ptr<AudioDevice> WasapiAudioDeviceManager::createAudioDevice(LPCWSTR deviceId, EDataFlow * dataFlow)
		{
			ComInitializer initializer;
			ComPtr<IMMDevice> pDevice;
			ComPtr<IMMEndpoint> endpoint;
			ComPtr<IPropertyStore> propertyStore;
			EDataFlow flow;
			PROPVARIANT pv;
			PropVariantInit(&pv);
			HRESULT hr;

			hr = deviceEnumerator->GetDevice(deviceId, &pDevice);
			THROW_IF_FAILED(hr, "WASAPI: Enumerator get device with id: %S failed", deviceId);

			hr = pDevice->QueryInterface(__uuidof(IMMEndpoint), (void**)&endpoint);
			THROW_IF_FAILED(hr, "WASAPI: Device get endpoint failed");

			hr = endpoint->GetDataFlow(&flow);
			THROW_IF_FAILED(hr, "WASAPI: Endpoint get data flow failed");

			hr = pDevice->OpenPropertyStore(STGM_READ, &propertyStore);
			THROW_IF_FAILED(hr, "WASAPI: Device open property store failed");

			hr = propertyStore->GetValue(PKEY_Device_FriendlyName, &pv);
			THROW_IF_FAILED(hr, "WASAPI: PropertyStore get friendly name failed");

			std::string id = WideStrToStr(deviceId);
			std::string name = WideStrToStr(pv.pwszVal);

			AudioDevicePtr device = nullptr;

			if (flow == eCapture) {
				device = std::make_shared<AudioDevice>(name, id);
			}
			else if (flow == eRender) {
				device = std::make_shared<AudioDevice>(name, id);
			}

			if (dataFlow != nullptr) {
				*dataFlow = flow;
			}

			PropVariantClear(&pv);

			return device;
		}

		template <typename T>
		void WasapiAudioDeviceManager::removeAudioDevice(DeviceList<T> & devices, std::string id)
		{
			auto predicate = [id](const AudioDevicePtr & dev) {
				return id == dev->getDescriptor();
			};

			T removed = devices.removeDevice(predicate);

			if (removed) {
				notifyDeviceDisconnected(removed);
			}
		}

		ULONG WasapiAudioDeviceManager::AddRef()
		{
			// We hold a reference to ourselves (static singleton).
			return 1;
		}

		ULONG WasapiAudioDeviceManager::Release()
		{
			// We hold a reference to ourselves (static singleton).
			return 1;
		}

		HRESULT WasapiAudioDeviceManager::QueryInterface(REFIID iid, void ** object)
		{
			if (object == nullptr) {
				return E_POINTER;
			}

			if (iid == IID_IUnknown || iid == __uuidof(IMMNotificationClient)) {
				*object = static_cast<IMMNotificationClient *>(this);
			}
			else {
				*object = nullptr;
				return E_NOINTERFACE;
			}

			AddRef();

			return S_OK;
		}

		HRESULT WasapiAudioDeviceManager::OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR deviceId)
		{
			RTC_LOG(LS_INFO) << "WASAPI: Default device changed (" << RoleToStr(role).c_str() << "): " << deviceId;

			std::string id;

			if (deviceId != nullptr) {
				id = WideStrToStr(deviceId);
			}

			auto predicate = [id](const AudioDevicePtr & dev) {
				return id == dev->getDescriptor();
			};

			if (flow == eCapture) {
				if (deviceId == nullptr) {
					setDefaultCaptureDevice(nullptr);
					return S_OK;
				}

				AudioDevicePtr found = captureDevices.findDevice(predicate);

				if (found) {
					setDefaultCaptureDevice(found);
				}
			}
			else if (flow == eRender) {
				if (deviceId == nullptr) {
					setDefaultPlaybackDevice(nullptr);
					return S_OK;
				}

				AudioDevicePtr found = playbackDevices.findDevice(predicate);

				if (found) {
					setDefaultPlaybackDevice(found);
				}
			}

			return S_OK;
		}

		HRESULT WasapiAudioDeviceManager::OnDeviceAdded(LPCWSTR deviceId)
		{
			RTC_LOG(LS_INFO) << "WASAPI: Device added: " << deviceId;

			addDevice(deviceId);

			return S_OK;
		}

		HRESULT WasapiAudioDeviceManager::OnDeviceRemoved(LPCWSTR deviceId)
		{
			RTC_LOG(LS_INFO) << "WASAPI: Device removed: " << deviceId;

			removeDevice(deviceId);

			return S_OK;
		}

		HRESULT WasapiAudioDeviceManager::OnDeviceStateChanged(LPCWSTR deviceId, DWORD newState)
		{
			RTC_LOG(LS_INFO) << "WASAPI: Device state changed: " << deviceId;

			if (deviceId == nullptr) {
				return S_OK;
			}

			switch (newState) {
				case DEVICE_STATE_ACTIVE:
					addDevice(deviceId);
					break;

				case DEVICE_STATE_DISABLED:
				case DEVICE_STATE_NOTPRESENT:
				case DEVICE_STATE_UNPLUGGED:
					removeDevice(deviceId);
					break;
			}

			return S_OK;
		}

		HRESULT WasapiAudioDeviceManager::OnPropertyValueChanged(LPCWSTR /*deviceId*/, const PROPERTYKEY /*key*/)
		{
			return S_OK;
		}
	}
}