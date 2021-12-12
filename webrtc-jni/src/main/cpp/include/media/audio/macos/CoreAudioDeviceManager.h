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

#ifndef JNI_WEBRTC_MEDIA_CORE_AUDIO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_CORE_AUDIO_DEVICE_MANAGER_H_

#include "media/audio/AudioDeviceManager.h"

#include <CoreAudio/CoreAudio.h>

namespace jni
{
	namespace avdev
	{
		class CoreAudioDeviceManager : public AudioDeviceManager
		{
			public:
				CoreAudioDeviceManager();
				~CoreAudioDeviceManager();

				std::set<AudioDevicePtr> getAudioCaptureDevices() override;
				std::set<AudioDevicePtr> getAudioPlaybackDevices() override;

				AudioDevicePtr getDefaultAudioCaptureDevice() override;
				AudioDevicePtr getDefaultAudioPlaybackDevice() override;

			private:
				void enumerateDevices(const AudioObjectPropertyScope & scope);
				void onDevicesChanged();
				void onDefaultDeviceChanged(const AudioObjectPropertyScope & scope, DeviceList<AudioDevicePtr> & devices, const AudioDevicePtr & device);
				void checkDeviceGone(DeviceList<AudioDevicePtr> & devices, AudioDeviceID * devIDs, const int numDevIDs, const AudioObjectPropertyScope & scope);
				AudioDevicePtr createDefaultAudioDevice(const AudioObjectPropertyScope & scope);
				AudioDevicePtr createAudioDevice(const AudioDeviceID & deviceID, const AudioObjectPropertyScope & scope);
				bool insertAudioDevice(const AudioDevicePtr & device, const AudioObjectPropertyScope & scope);
				int getChannelCount(const AudioDeviceID & deviceID, const AudioObjectPropertyScope & scope);
				AudioDeviceID getDefaultDeviceID(const AudioObjectPropertyScope & scope);

				static OSStatus deviceListenerProc(AudioObjectID objectID, UInt32 numberAddresses, const AudioObjectPropertyAddress addresses[], void * clientData);
		};
	}
}

#endif
