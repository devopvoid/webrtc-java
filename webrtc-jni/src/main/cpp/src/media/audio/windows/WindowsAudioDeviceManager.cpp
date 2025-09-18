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

#include "Exception.h"
#include "media/audio/windows/WindowsAudioDeviceManager.h"
#include "platform/windows/MFInitializer.h"
#include "platform/windows/WinUtils.h"
#include "WebRTCContext.h"

#include "Functiondiscoverykeys_devpkey.h"

#include "api/audio/create_audio_device_module.h"
#include "api/scoped_refptr.h"
#include "modules/audio_device/include/audio_device.h"
#include "rtc_base/logging.h"

#include <stdlib.h>

namespace jni
{
	namespace avdev
	{
		WindowsAudioDeviceManager::WindowsAudioDeviceManager() :
			deviceEnumerator()
		{
			HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)&deviceEnumerator);
			THROW_IF_FAILED(hr, "MMF: Create device enumerator failed");

			deviceEnumerator->RegisterEndpointNotificationCallback(this);
		}

		WindowsAudioDeviceManager::~WindowsAudioDeviceManager()
		{
			if (deviceEnumerator) {
				deviceEnumerator->UnregisterEndpointNotificationCallback(this);
			}
		}

		AudioDevicePtr WindowsAudioDeviceManager::getDefaultAudioCaptureDevice()
		{
			return createDefaultAudioDevice(EDataFlow::eCapture);
		}

		AudioDevicePtr WindowsAudioDeviceManager::getDefaultAudioPlaybackDevice()
		{
			return createDefaultAudioDevice(EDataFlow::eRender);
		}

		std::set<AudioDevicePtr> WindowsAudioDeviceManager::getAudioCaptureDevices()
		{
			if (!comInitializer.isInitialized()) {
				throw jni::Exception("Invalid COM thread model change (MTA->STA)");
			}

			if (captureDevices.empty()) {
				enumerateDevices(eCapture);
			}

			return captureDevices.devices();
		}

		std::set<AudioDevicePtr> WindowsAudioDeviceManager::getAudioPlaybackDevices()
		{
			if (!comInitializer.isInitialized()) {
				throw jni::Exception("Invalid COM thread model change (MTA->STA)");
			}

			if (playbackDevices.empty()) {
				enumerateDevices(eRender);
			}

			return playbackDevices.devices();
		}

		void WindowsAudioDeviceManager::enumerateDevices(EDataFlow dataFlow)
		{
			jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

			webrtc::scoped_refptr<webrtc::AudioDeviceModule> audioModule = webrtc::CreateAudioDeviceModule(
				context->webrtcEnv, webrtc::AudioDeviceModule::kPlatformDefaultAudio);

			if (!audioModule) {
				throw jni::Exception("Create AudioDeviceModule failed");
			}
			if (audioModule->Init() != 0) {
				throw jni::Exception("Initialize AudioDeviceModule failed");
			}

			bool enumCaptureDevices = false;
			bool enumRenderDevices = false;

			if (dataFlow == EDataFlow::eAll || dataFlow == EDataFlow::eCapture) {
				enumCaptureDevices = true;
			}
			if (dataFlow == EDataFlow::eAll || dataFlow == EDataFlow::eRender) {
				enumRenderDevices = true;
			}

			char name[webrtc::kAdmMaxDeviceNameSize];
			char guid[webrtc::kAdmMaxGuidSize];

			if (enumCaptureDevices) {
				int16_t deviceCount = audioModule->RecordingDevices();
                // fix duplicated item when device reconect to port. The comparator does not always work correctly.
                if (deviceCount > 0) {
                    captureDevices.clearDevices();
                }

				for (int i = 0; i < deviceCount; ++i) {
					if (audioModule->RecordingDeviceName(i, name, guid) == 0) {
						AudioDevicePtr device = std::make_shared<AudioDevice>(name, guid);

                        fillAdditionalTypes(device);

                        insertAudioDevice(device, EDataFlow::eCapture);
					}
				}
			}
			if (enumRenderDevices) {
				int16_t deviceCount = audioModule->PlayoutDevices();

				// Fix duplicated item when device reconect to port. The comparator does not always work correctly.
				if (deviceCount > 0) {
                    playbackDevices.clearDevices();
                }

				for (int i = 0; i < deviceCount; ++i) {
					if (audioModule->PlayoutDeviceName(i, name, guid) == 0) {
						AudioDevicePtr device = std::make_shared<AudioDevice>(name, guid);

                        fillAdditionalTypes(device);

                        insertAudioDevice(device, EDataFlow::eRender);
					}
				}
			}
		}

		void WindowsAudioDeviceManager::fillAdditionalTypes(AudioDevicePtr device)
		{
		        MFInitializer initializer;
                ComPtr<IMMDevice> pDevice;
                ComPtr<IMMEndpoint> endpoint;
                ComPtr<IPropertyStore> propertyStore;
                HRESULT hr;
                PROPVARIANT ff;
                PropVariantInit(&ff);

                // convert std::string to LPWSTR
                wchar_t* wDeviceID=new wchar_t[4096];
                size_t convertedChars = 0;
                mbstowcs_s(&convertedChars, wDeviceID, device->getDescriptor().length() + 1, device->getDescriptor().c_str(), _TRUNCATE);

                hr = deviceEnumerator->GetDevice(wDeviceID, &pDevice);
                delete[] wDeviceID;

                THROW_IF_FAILED(hr, "Audio Device Manager: Enumerator get device with ID: %S failed", device->getDescriptor().c_str());

                hr = pDevice->QueryInterface(__uuidof(IMMEndpoint), (void**)&endpoint);
                THROW_IF_FAILED(hr, "Audio Device Manager: Device get endpoint failed");

                hr = pDevice->OpenPropertyStore(STGM_READ, &propertyStore);
                THROW_IF_FAILED(hr, "Audio Device Manager: Device open property store failed");

                hr = propertyStore->GetValue(PKEY_AudioEndpoint_FormFactor, &ff);
                THROW_IF_FAILED(hr, "Audio Device Manager: PropertyStore get form factor property name failed");

                EndpointFormFactor formFactor = static_cast<EndpointFormFactor>(ff.uintVal);
                PropVariantClear(&ff);

                device->setDeviceTransport(getActualTransport(formFactor));
                device->setDeviceFormFactor(getActualFormFactor(formFactor));
		}

		void WindowsAudioDeviceManager::addDevice(LPCWSTR deviceId)
		{
			if (deviceId == nullptr) {
				return;
			}

			EDataFlow dataFlow;
			std::shared_ptr<AudioDevice> device = createAudioDevice(deviceId, &dataFlow);
			bool inserted = insertAudioDevice(device, dataFlow);

			if (inserted) {
				notifyDeviceConnected(device);
			}
		}

		void WindowsAudioDeviceManager::removeDevice(LPCWSTR deviceId)
		{
			if (deviceId == nullptr)
				return;

			std::string id = WideStrToStr(deviceId);

			removeAudioDevice(captureDevices, id, eCapture);
			removeAudioDevice(playbackDevices, id, eRender);
		}

		AudioDevicePtr WindowsAudioDeviceManager::createDefaultAudioDevice(const EDataFlow& dataFlow)
		{
			MFInitializer initializer;
			ComPtr<IMMDeviceCollection> deviceCollection;
			ComPtr<IMMDevice> defaultDevice;
			ComPtr<IPropertyStore> propertyStore;
			LPWSTR defaultDeviceId = nullptr;
			PROPVARIANT pv;
			PropVariantInit(&pv);

			HRESULT hr = deviceEnumerator->GetDefaultAudioEndpoint(dataFlow, eMultimedia, &defaultDevice);
			THROW_IF_FAILED(hr, "MMF: Get default audio endpoint failed");

			hr = defaultDevice->GetId(&defaultDeviceId);
			THROW_IF_FAILED(hr, "MMF: Get default device ID failed");

			hr = defaultDevice->OpenPropertyStore(STGM_READ, &propertyStore);
			THROW_IF_FAILED(hr, "MMF: Device open property store failed");

			hr = propertyStore->GetValue(PKEY_Device_FriendlyName, &pv);
			THROW_IF_FAILED(hr, "MMF: PropertyStore get friendly name failed");

			std::string id = WideStrToStr(defaultDeviceId);
			std::string name = WideStrToStr(pv.pwszVal);

			AudioDevicePtr device = std::make_shared<AudioDevice>(name, id);
			if (dataFlow == eCapture) {
                device->directionType = AudioDeviceDirectionType::adtCapture;
            } else {
                device->directionType = AudioDeviceDirectionType::adtRender;
            }

			fillAdditionalTypes(device);

			PropVariantClear(&pv);

			return device;
		}

		AudioDevicePtr WindowsAudioDeviceManager::createAudioDevice(LPCWSTR deviceId, EDataFlow * dataFlow)
		{
			MFInitializer initializer;
			ComPtr<IMMDevice> pDevice;
			ComPtr<IMMEndpoint> endpoint;
			ComPtr<IPropertyStore> propertyStore;
			EDataFlow flow;
			PROPVARIANT pv;
			PropVariantInit(&pv);
			HRESULT hr;

			hr = deviceEnumerator->GetDevice(deviceId, &pDevice);
			THROW_IF_FAILED(hr, "MMF: Enumerator get device with ID: %S failed", deviceId);

			hr = pDevice->QueryInterface(__uuidof(IMMEndpoint), (void**)&endpoint);
			THROW_IF_FAILED(hr, "MMF: Device get endpoint failed");

			hr = endpoint->GetDataFlow(&flow);
			THROW_IF_FAILED(hr, "MMF: Endpoint get data flow failed");

			hr = pDevice->OpenPropertyStore(STGM_READ, &propertyStore);
			THROW_IF_FAILED(hr, "MMF: Device open property store failed");

			hr = propertyStore->GetValue(PKEY_Device_FriendlyName, &pv);
			THROW_IF_FAILED(hr, "MMF: PropertyStore get friendly name failed");

			std::string id = WideStrToStr(deviceId);
			std::string name = WideStrToStr(pv.pwszVal);

			AudioDevicePtr device = std::make_shared<AudioDevice>(name, id);

            fillAdditionalTypes(device);

			if (dataFlow != nullptr) {
				*dataFlow = flow;
			}

			PropVariantClear(&pv);

			return device;
		}

		bool WindowsAudioDeviceManager::insertAudioDevice(AudioDevicePtr device, EDataFlow dataFlow)
		{
			if (device == nullptr) {
				return false;
			}

			if (dataFlow == eCapture) {
                device->directionType = jni::avdev::AudioDeviceDirectionType::adtCapture;

				captureDevices.insertDevice(device);
				return true;
			}
			else if (dataFlow == eRender) {
			    device->directionType = jni::avdev::AudioDeviceDirectionType::adtRender;

				playbackDevices.insertDevice(device);
				return true;
			}

			return false;
		}

		void WindowsAudioDeviceManager::removeAudioDevice(DeviceList<AudioDevicePtr> & devices, std::string id, EDataFlow dataFlow)
		{
			auto predicate = [id](const AudioDevicePtr & dev) {
				return id == dev->getDescriptor();
			};

			AudioDevicePtr removed = devices.removeDevice(predicate);

			if (removed) {
			    if (dataFlow == eCapture) {
                    removed->directionType = AudioDeviceDirectionType::adtCapture;
                } else {
                    removed->directionType = AudioDeviceDirectionType::adtRender;
                }

				notifyDeviceDisconnected(removed);
			}
		}

		ULONG WindowsAudioDeviceManager::AddRef()
		{
			return 1;
		}

		ULONG WindowsAudioDeviceManager::Release()
		{
			return 1;
		}

		HRESULT WindowsAudioDeviceManager::QueryInterface(REFIID iid, void ** object)
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

		HRESULT WindowsAudioDeviceManager::OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR deviceId)
		{
			RTC_LOG(LS_INFO) << "MMF: Default device changed (" << RoleToStr(role).c_str() << "): " << deviceId;

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

		HRESULT WindowsAudioDeviceManager::OnDeviceAdded(LPCWSTR deviceId)
		{
			RTC_LOG(LS_INFO) << "MMF: Device added: " << deviceId;

			addDevice(deviceId);

			return S_OK;
		}

		HRESULT WindowsAudioDeviceManager::OnDeviceRemoved(LPCWSTR deviceId)
		{
			RTC_LOG(LS_INFO) << "MMF: Device removed: " << deviceId;

			removeDevice(deviceId);

			return S_OK;
		}

		HRESULT WindowsAudioDeviceManager::OnDeviceStateChanged(LPCWSTR deviceId, DWORD newState)
		{
			RTC_LOG(LS_INFO) << "MMF: Device state changed: " << deviceId;

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

		HRESULT WindowsAudioDeviceManager::OnPropertyValueChanged(LPCWSTR /*deviceId*/, const PROPERTYKEY /*key*/)
		{
			return S_OK;
		}

		DeviceFormFactor WindowsAudioDeviceManager::getActualFormFactor(EndpointFormFactor formFactor) {
		    switch (formFactor) {
                case RemoteNetworkDevice:
                case UnknownDigitalPassthrough:
                case EndpointFormFactor_enum_count:
                case UnknownFormFactor:
                case LineLevel:
                case SPDIF:
                    return DeviceFormFactor::ffUnknown;
                case Speakers:
                    return DeviceFormFactor::ffSpeaker;
                case Headphones:
                    return DeviceFormFactor::ffHeadphone;
                case Headset:
                case Handset:
                    return DeviceFormFactor::ffHeadset;
                case Microphone:
                    return DeviceFormFactor::ffMicrophone;
                case DigitalAudioDisplayDevice: // for audio devices this means hdmi
                    return DeviceFormFactor::ffUnknown;
                default:
                    return DeviceFormFactor::ffUnknown;
            }
		}

		DeviceTransport WindowsAudioDeviceManager::getActualTransport(EndpointFormFactor formFactor) {
		    // for Windows, only HDMI can be reliably determined
		    switch (formFactor) {
		        case DigitalAudioDisplayDevice:
		            return DeviceTransport::trHdmi;
		        default:
                    return DeviceTransport::trUnknown;
		    }
		}
	}
}