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

#include "media/audio/macos/CoreAudioDeviceManager.h"
#include "platform/macos/MacUtils.h"

#include "rtc_base/logging.h"

#include <Foundation/Foundation.h>

namespace jni
{
	namespace avdev
	{
		CoreAudioDeviceManager::CoreAudioDeviceManager() :
			AudioDeviceManager()
		{
			AudioObjectPropertyAddress pa;
			pa.mSelector = kAudioObjectPropertySelectorWildcard;
			pa.mScope = kAudioObjectPropertyScopeWildcard;
			pa.mElement = kAudioObjectPropertyElementWildcard;

			OSStatus status = AudioObjectAddPropertyListener(kAudioObjectSystemObject, &pa, deviceListenerProc, this);
			ThrowIfFailed(status, "CoreAudio: Add device listener failed");
		}

		CoreAudioDeviceManager::~CoreAudioDeviceManager()
		{
			AudioObjectPropertyAddress pa;
			pa.mSelector = kAudioObjectPropertySelectorWildcard;
			pa.mScope = kAudioObjectPropertyScopeWildcard;
			pa.mElement = kAudioObjectPropertyElementWildcard;

			AudioObjectRemovePropertyListener(kAudioObjectSystemObject, &pa, deviceListenerProc, this);
		}

		std::set<AudioDevicePtr> CoreAudioDeviceManager::getAudioCaptureDevices()
		{
			if (captureDevices.empty()) {
				enumerateDevices(kAudioObjectPropertyScopeInput);
			}

			return captureDevices.devices();
		}

		std::set<AudioDevicePtr> CoreAudioDeviceManager::getAudioPlaybackDevices()
		{
			if (playbackDevices.empty()) {
				enumerateDevices(kAudioObjectPropertyScopeOutput);
			}

			return playbackDevices.devices();
		}

		AudioDevicePtr CoreAudioDeviceManager::getDefaultAudioCaptureDevice()
        {
        	return createDefaultAudioDevice(kAudioObjectPropertyScopeInput);
        }

        AudioDevicePtr CoreAudioDeviceManager::getDefaultAudioPlaybackDevice()
        {
        	return createDefaultAudioDevice(kAudioObjectPropertyScopeOutput);
        }

		void CoreAudioDeviceManager::enumerateDevices(const AudioObjectPropertyScope & scope) {
			// Get all devices.
			AudioObjectPropertyAddress pa;
			pa.mSelector = kAudioHardwarePropertyDevices;
			pa.mScope = kAudioObjectPropertyScopeGlobal;
			pa.mElement = kAudioObjectPropertyElementMaster;

			UInt32 dataSize;

			OSStatus status = AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &pa, 0, nullptr, &dataSize);
			ThrowIfFailed(status, "CoreAudio: Enumerate audio endpoints failed");

			const int numDevices = dataSize / sizeof(AudioDeviceID);
			AudioDeviceID * devIDs = new AudioDeviceID[numDevices];

			status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &pa, 0, nullptr, &dataSize, devIDs);
			ThrowIfFailed(status, "CoreAudio: Enumerate audio endpoints failed");

			AudioDeviceID defaultID = getDefaultDeviceID(scope);

			for (int i = 0; i < numDevices; i++) {
				AudioDevicePtr device = createAudioDevice(devIDs[i], scope);

				if (device != nullptr) {
					insertAudioDevice(device, scope);

					// Set default device.
					bool isDefault = (defaultID == devIDs[i]);

					if (isDefault && scope == kAudioObjectPropertyScopeInput) {
						setDefaultCaptureDevice(device);
					}
					else if (isDefault && scope == kAudioObjectPropertyScopeOutput) {
						setDefaultPlaybackDevice(device);
					}
				}
			}

			delete[] devIDs;
		}

		void CoreAudioDeviceManager::onDevicesChanged()
		{
			// Get all devices.
			AudioObjectPropertyAddress pa;
			pa.mSelector = kAudioHardwarePropertyDevices;
			pa.mScope = kAudioObjectPropertyScopeGlobal;
			pa.mElement = kAudioObjectPropertyElementMaster;

			UInt32 dataSize;

			OSStatus status = AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &pa, 0, nullptr, &dataSize);
			ThrowIfFailed(status, "CoreAudio: Enumerate audio endpoints failed");

			const int numDevices = dataSize / sizeof(AudioDeviceID);
			AudioDeviceID * devIDs = new AudioDeviceID[numDevices];

			status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &pa, 0, nullptr, &dataSize, devIDs);
			ThrowIfFailed(status, "CoreAudio: Enumerate audio endpoints failed");

			// Check, if a new device is available.
			for (int i = 0; i < numDevices; i++) {
				bool found = false;

				for (const AudioDevicePtr & dev : captureDevices.devices()) {
					AudioDeviceID devID = std::stoi(dev->getDescriptor());

					if (devIDs[i] == devID) {
						found = true;
						break;
					}
				}

				for (const AudioDevicePtr & dev : playbackDevices.devices()) {
					AudioDeviceID devID = std::stoi(dev->getDescriptor());

					if (devIDs[i] == devID) {
						found = true;
						break;
					}
				}

				if (!found) {
					// New device found.
					unsigned inChannels = getChannelCount(devIDs[i], kAudioObjectPropertyScopeInput);
					unsigned outChannels = getChannelCount(devIDs[i], kAudioObjectPropertyScopeOutput);

					if (inChannels > 0) {
						AudioObjectPropertyScope scope = kAudioObjectPropertyScopeInput;
						AudioDevicePtr device = createAudioDevice(devIDs[i], scope);

						if (device != nullptr) {
							insertAudioDevice(device, scope);

							// Update default capture device.
							AudioDevicePtr def = getDefaultCaptureDevice();
							onDefaultDeviceChanged(scope, captureDevices, def);

							notifyDeviceConnected(device);
						}
					}
					if (outChannels > 0) {
						AudioObjectPropertyScope scope = kAudioObjectPropertyScopeOutput;
						AudioDevicePtr device = createAudioDevice(devIDs[i], scope);

						if (device != nullptr) {
							insertAudioDevice(device, scope);

							// Update default playback device.
							AudioDevicePtr def = getDefaultPlaybackDevice();
							onDefaultDeviceChanged(scope, playbackDevices, def);

							notifyDeviceConnected(device);
						}
					}
				}
			}

			// Check, if a device was disconnected.
			checkDeviceGone(captureDevices, devIDs, numDevices, kAudioObjectPropertyScopeInput);
			checkDeviceGone(playbackDevices, devIDs, numDevices, kAudioObjectPropertyScopeOutput);

			delete[] devIDs;
		}

		void CoreAudioDeviceManager::onDefaultDeviceChanged(const AudioObjectPropertyScope & scope, DeviceList<AudioDevicePtr> & devices, const AudioDevicePtr & device)
		{
			AudioDeviceID defaultID = getDefaultDeviceID(scope);
			AudioDeviceID deviceID = (device == nullptr) ? 0 : std::stoi(device->getDescriptor());

			if (defaultID != deviceID) {
				auto predicate = [defaultID](const AudioDevicePtr & dev) {
					AudioDeviceID devID = std::stoi(dev->getDescriptor());

					return defaultID == devID;
				};

				AudioDevicePtr found = devices.findDevice(predicate);

				if (found == nullptr) {
					RTC_LOG(LS_WARNING) << "CoreAudio: Find device failed";
					return;
				}

				if (scope == kAudioObjectPropertyScopeInput) {
					setDefaultCaptureDevice(found);
				}
				else if (scope == kAudioObjectPropertyScopeOutput) {
					setDefaultPlaybackDevice(found);
				}
			}
		}

		void CoreAudioDeviceManager::checkDeviceGone(DeviceList<AudioDevicePtr> & devices, AudioDeviceID * devIDs, const int numDevIDs, const AudioObjectPropertyScope & scope)
		{
			auto predicate = [devIDs, numDevIDs](const AudioDevicePtr & dev) {
				AudioDeviceID devID = std::stoi(dev->getDescriptor());
				bool found = false;

				for (int i = 0; i < numDevIDs; i++) {
					if (devIDs[i] == devID) {
						found = true;
						break;
					}
				}

				return !found;
			};

			AudioDevicePtr removed = devices.removeDevice(predicate);

			if (removed != nullptr) {
				if (scope == kAudioObjectPropertyScopeInput) {
					AudioDevicePtr def = getDefaultAudioCaptureDevice();

					if (removed == def) {
						onDefaultDeviceChanged(scope, captureDevices, def);
					}
				}
				else if (scope == kAudioObjectPropertyScopeOutput) {
					AudioDevicePtr def = getDefaultAudioPlaybackDevice();

					if (removed == def) {
						onDefaultDeviceChanged(scope, playbackDevices, def);
					}
				}

				notifyDeviceDisconnected(removed);
			}
		}

		AudioDevicePtr CoreAudioDeviceManager::createDefaultAudioDevice(const AudioObjectPropertyScope & scope)
		{
			AudioDeviceID defaultID = getDefaultDeviceID(scope);

			return createAudioDevice(defaultID, scope);
		}

		AudioDevicePtr CoreAudioDeviceManager::createAudioDevice(const AudioDeviceID & deviceID, const AudioObjectPropertyScope & scope) {
			AudioDevicePtr device = nullptr;
			CFStringRef devNameRef = nullptr;
			UInt32 dataSize = sizeof(devNameRef);

			AudioObjectPropertyAddress pa;
			pa.mSelector = kAudioObjectPropertyName;
			pa.mScope = kAudioObjectPropertyScopeGlobal;
			pa.mElement = kAudioObjectPropertyElementMaster;

			OSStatus status = AudioObjectGetPropertyData(deviceID, &pa, 0, nullptr, &dataSize, &devNameRef);
			ThrowIfFailed(status, "CoreAudio: Get device name failed");

			std::string name = CFStringRefToUTF8(devNameRef);
			std::string id = std::to_string(deviceID);

			CFRelease(devNameRef);

			unsigned channels = getChannelCount(deviceID, scope);

			if (channels > 0) {
				if (scope == kAudioObjectPropertyScopeOutput) {
					device = std::make_shared<AudioDevice>(name, id);
				}
				else if (scope == kAudioObjectPropertyScopeInput) {
					device = std::make_shared<AudioDevice>(name, id);
				}
			}

			return device;
		}

		bool CoreAudioDeviceManager::insertAudioDevice(const AudioDevicePtr & device, const AudioObjectPropertyScope & scope)
		{
			if (device == nullptr) {
				return false;
			}

			if (scope == kAudioObjectPropertyScopeOutput) {
				return playbackDevices.insertDevice(device);
			}
			else if (scope == kAudioObjectPropertyScopeInput) {
				return captureDevices.insertDevice(device);
			}

			return false;
		}

		int CoreAudioDeviceManager::getChannelCount(const AudioDeviceID & deviceID, const AudioObjectPropertyScope & scope) {
			AudioObjectPropertyAddress pa;
			pa.mSelector = kAudioDevicePropertyStreamConfiguration;
			pa.mElement = kAudioObjectPropertyElementMaster;
			pa.mScope = scope;

			int channels = 0;
			UInt32 dataSize;

			if (AudioObjectGetPropertyDataSize(deviceID, &pa, 0, nullptr, &dataSize) == noErr) {
				AudioBufferList * buffers = new AudioBufferList[dataSize];

				if (AudioObjectGetPropertyData(deviceID, &pa, 0, nullptr, &dataSize, buffers) == noErr) {
					for (int i = 0; i < buffers->mNumberBuffers; i++) {
						channels += buffers->mBuffers[i].mNumberChannels;
					}
				}

				delete[] buffers;
			}

			return channels;
		}

		AudioDeviceID CoreAudioDeviceManager::getDefaultDeviceID(const AudioObjectPropertyScope & scope) {
			AudioObjectPropertyAddress pa;
			pa.mScope = kAudioObjectPropertyScopeGlobal;
			pa.mElement = kAudioObjectPropertyElementMaster;

			switch (scope) {
				case kAudioObjectPropertyScopeInput:
					pa.mSelector = kAudioHardwarePropertyDefaultInputDevice;
					break;

				case kAudioObjectPropertyScopeOutput:
					pa.mSelector = kAudioHardwarePropertyDefaultOutputDevice;
					break;
			}

			AudioDeviceID defaultID = 0;
			UInt32 dataSize = sizeof(AudioDeviceID);
			OSStatus status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &pa, 0, nullptr, &dataSize, &defaultID);

			if (status != noErr) {
				RTC_LOG(LS_ERROR) << "CoreAudio: Get default device ID failed: Status = " << status;
			}

			return defaultID;
		}

		OSStatus CoreAudioDeviceManager::deviceListenerProc(AudioObjectID objectID, UInt32 numberAddresses, const AudioObjectPropertyAddress addresses[], void * clientData) {
			CoreAudioDeviceManager * manager = static_cast<CoreAudioDeviceManager *>(clientData);

			for (int i = 0; i < numberAddresses; i++) {
				switch (addresses[i].mSelector) {
					case kAudioHardwarePropertyDevices:
					{
						manager->onDevicesChanged();
						break;
					}

					case kAudioHardwarePropertyDefaultInputDevice:
					{
						AudioDevicePtr def = manager->getDefaultCaptureDevice();
						manager->onDefaultDeviceChanged(kAudioObjectPropertyScopeInput, manager->captureDevices, def);
						break;
					}

					case kAudioHardwarePropertyDefaultOutputDevice:
					{
						AudioDevicePtr defp = manager->getDefaultPlaybackDevice();
						manager->onDefaultDeviceChanged(kAudioObjectPropertyScopeOutput, manager->playbackDevices, defp);
						break;
					}
				}
			}

			return noErr;
		}
	}
}
