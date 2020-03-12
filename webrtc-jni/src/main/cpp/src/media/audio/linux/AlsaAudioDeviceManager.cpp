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

#include "media/audio/linux/AlsaAudioDeviceManager.h"

#include "rtc_base/logging.h"

#include <stdio.h>
#include <sstream>

namespace jni
{
	namespace avdev
	{
		AlsaAudioDeviceManager::AlsaAudioDeviceManager()
		{
			getAudioCaptureDevices();
			getAudioPlaybackDevices();
		}

		AlsaAudioDeviceManager::~AlsaAudioDeviceManager()
		{
		}

		std::set<AudioDevicePtr> AlsaAudioDeviceManager::getAudioCaptureDevices() {
			if (captureDevices.empty()) {
				enumerateDevices(SND_PCM_STREAM_CAPTURE);
			}

			return captureDevices.devices();
		}

		std::set<AudioDevicePtr> AlsaAudioDeviceManager::getAudioPlaybackDevices() {
			if (playbackDevices.empty()) {
				enumerateDevices(SND_PCM_STREAM_PLAYBACK);
			}

			return playbackDevices.devices();
		}

		void AlsaAudioDeviceManager::enumerateDevices(snd_pcm_stream_t stream)
		{
			snd_ctl_t * handle;
			snd_ctl_card_info_t * info;
			snd_pcm_info_t * pcminfo;
			snd_ctl_card_info_alloca(&info);
			snd_pcm_info_alloca(&pcminfo);

			int dev;
			int error;
			int card = -1;

			while (snd_card_next(&card) == 0 && card > -1) {
				char hwname[32];
				snprintf(hwname, sizeof(hwname), "hw:%d", card);

				if ((error = snd_ctl_open(&handle, hwname, 0)) < 0) {
					RTC_LOG(LS_ERROR) << "ALSA: Control open failed (" << card << "): " << snd_strerror(error);
					continue;
				}
				if ((error = snd_ctl_card_info(handle, info)) < 0) {
					RTC_LOG(LS_ERROR) << "ALSA: Get card related information failed (" << card << "): " << snd_strerror(error);
					snd_ctl_close(handle);

					continue;
				}

				dev = -1;

				while (1) {
					if ((error = snd_ctl_pcm_next_device(handle, &dev)) < 0) {
						RTC_LOG(LS_ERROR) << "ALSA: Get next PCM device number failed (" << card << "): " << snd_strerror(error);
					}
					if (dev < 0) {
						break;
					}

					snd_pcm_info_set_device(pcminfo, dev);
					snd_pcm_info_set_subdevice(pcminfo, 0);
					snd_pcm_info_set_stream(pcminfo, stream);

					if ((error = snd_ctl_pcm_info(handle, pcminfo)) < 0) {
						if (error != -ENOENT) {
							RTC_LOG(LS_ERROR) << "ALSA: Get PCM device info failed (" << card << "): " << snd_strerror(error);
						}
						continue;
					}

					std::ostringstream idStream;
					idStream << "plughw:" << card << "," << dev;

					std::ostringstream nameStream;
					nameStream << snd_ctl_card_info_get_name(info) << " [" << snd_pcm_info_get_name(pcminfo) << "]";

					std::string id = idStream.str();
					std::string name = nameStream.str();
					AudioDevicePtr device = nullptr;

					if (stream == SND_PCM_STREAM_CAPTURE) {
						device = std::make_shared<AudioDevice>(name, id);
					}
					else if (stream == SND_PCM_STREAM_PLAYBACK) {
						device = std::make_shared<AudioDevice>(name, id);
					}

					insertDevice(device, stream);
				}
				snd_ctl_close(handle);
			}
		}

		bool AlsaAudioDeviceManager::insertDevice(AudioDevicePtr device, snd_pcm_stream_t stream)
		{
			if (device == nullptr) {
				return false;
			}

			if (stream == SND_PCM_STREAM_CAPTURE) {
				captureDevices.insertDevice(device);
				return true;
			}
			else if (stream == SND_PCM_STREAM_PLAYBACK) {
				playbackDevices.insertDevice(device);
				return true;
			}

			return false;
		}
	}
}