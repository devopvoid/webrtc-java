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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_AUDIO_DEVICE_MANAGER_H_

#include "media/DeviceManager.h"
#include "media/DeviceList.h"
#include "media/audio/AudioDevice.h"

#include <mutex>
#include <set>

namespace jni
{
	namespace avdev
	{
		using AudioDevicePtr = std::shared_ptr<AudioDevice>;


		class AudioDeviceManager : public DeviceManager
		{
			public:
				AudioDeviceManager();
				virtual ~AudioDeviceManager() {};

				virtual AudioDevicePtr getDefaultAudioCaptureDevice();
				virtual AudioDevicePtr getDefaultAudioPlaybackDevice();

				virtual std::set<AudioDevicePtr> getAudioCaptureDevices() = 0;
				virtual std::set<AudioDevicePtr> getAudioPlaybackDevices() = 0;

			protected:
				void setDefaultCaptureDevice(AudioDevicePtr device);
				AudioDevicePtr getDefaultCaptureDevice();

				void setDefaultPlaybackDevice(AudioDevicePtr device);
				AudioDevicePtr getDefaultPlaybackDevice();

				DeviceList<AudioDevicePtr> captureDevices;
				DeviceList<AudioDevicePtr> playbackDevices;

			private:
				AudioDevicePtr defaultCaptureDevice;
				AudioDevicePtr defaultPlaybackDevice;
				std::mutex mutex;
		};
	}
}

#endif