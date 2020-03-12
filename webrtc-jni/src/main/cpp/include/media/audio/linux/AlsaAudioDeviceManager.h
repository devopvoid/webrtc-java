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

#ifndef JNI_WEBRTC_MEDIA_ALSA_AUDIO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_ALSA_AUDIO_DEVICE_MANAGER_H_

#include <alsa/asoundlib.h>

#include "media/audio/AudioDeviceManager.h"

namespace jni
{
	namespace avdev
	{
		class AlsaAudioDeviceManager : public AudioDeviceManager
		{
			public:
				AlsaAudioDeviceManager();
				~AlsaAudioDeviceManager();

				std::set<AudioDevicePtr> getAudioCaptureDevices() override;
				std::set<AudioDevicePtr> getAudioPlaybackDevices() override;

			private:
				void enumerateDevices(snd_pcm_stream_t stream);
				bool insertDevice(std::shared_ptr<AudioDevice> device, snd_pcm_stream_t stream);
		};
	}
}

#endif