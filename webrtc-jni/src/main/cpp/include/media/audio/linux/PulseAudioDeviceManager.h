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

#ifndef JNI_WEBRTC_MEDIA_PULSE_AUDIO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_PULSE_AUDIO_DEVICE_MANAGER_H_

#include "media/audio/AudioDeviceManager.h"

#include <pulse/pulseaudio.h>
#include <unordered_map>

namespace jni
{
	namespace avdev
	{
		class PulseAudioDeviceManager : public AudioDeviceManager
		{
			public:
				PulseAudioDeviceManager();
				~PulseAudioDeviceManager();

				AudioDevicePtr getDefaultAudioCaptureDevice() override;
				AudioDevicePtr getDefaultAudioPlaybackDevice() override;

				std::set<AudioDevicePtr> getAudioCaptureDevices() override;
				std::set<AudioDevicePtr> getAudioPlaybackDevices() override;

			private:
				void dispose();

				void iterate(pa_threaded_mainloop * main_loop, pa_operation * op);

				/* PulseAudio API callbacks. */
				static void stateCallback(pa_context * ctx, void * userdata);
				static void serverInfoCallback(pa_context * ctx, const pa_server_info * info, void * userdata);
				static void subscribeCallback(pa_context * ctx, pa_subscription_event_type_t t, uint32_t idx, void * userdata);
				static void getSourceInfoCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata);
				static void getSourceCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata);
				static void newSourceCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata);
				static void getSinkInfoCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata);
				static void getSinkCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata);
				static void newSinkCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata);

				void insertDevice(DeviceList<AudioDevicePtr> & devices, const char * name, const char * desc, uint32_t index, bool notify);
				void removeDevice(DeviceList<AudioDevicePtr> & devices, uint32_t index);

			private:
				pa_threaded_mainloop * mainloop;
				pa_context * context;

				std::string defaultCaptureName;
				std::string defaultCaptureDescName;
				std::string defaultPlaybackName;
				std::string defaultPlaybackDescName;

				std::unordered_map<uint32_t, AudioDevicePtr> deviceMap;
		};
	}
}

#endif