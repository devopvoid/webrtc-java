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

#include "media/audio/linux/PulseAudioDeviceManager.h"
#include "Exception.h"

namespace jni
{
	namespace avdev
	{
		PulseAudioDeviceManager::PulseAudioDeviceManager()
		{
			mainloop = pa_threaded_mainloop_new();

			if (mainloop == 0) {
				throw Exception("PulseAudio: Could not create threaded mainloop");
			}

			if (pa_threaded_mainloop_start(mainloop) != 0) {
				pa_threaded_mainloop_free(mainloop);

				throw Exception("PulseAudio: Could not start threaded mainloop");
			}

			pa_mainloop_api * mainloopApi = pa_threaded_mainloop_get_api(mainloop);
			context = pa_context_new(mainloopApi, "MediaDevices");

			if (!context) {
				pa_threaded_mainloop_free(mainloop);

				throw Exception("PulseAudio: Could not create context");
			}

			pa_context_set_state_callback(context, stateCallback, mainloop);
			pa_context_set_subscribe_callback(context, subscribeCallback, this);

			pa_threaded_mainloop_lock(mainloop);

			if (pa_context_connect(context, nullptr, PA_CONTEXT_NOFLAGS, nullptr) < 0) {
				pa_context_unref(context);
				pa_threaded_mainloop_free(mainloop);

				throw Exception("PulseAudio: Could not connect to the context");
			}

			while (true) {
				pa_context_state_t contextState = pa_context_get_state(context);

				if (contextState == PA_CONTEXT_FAILED || contextState == PA_CONTEXT_TERMINATED) {
					dispose();
					return;
				}
				if (contextState == PA_CONTEXT_READY) {
					break;
				}

				pa_threaded_mainloop_wait(mainloop);
			}

			int mask = PA_SUBSCRIPTION_MASK_SINK | PA_SUBSCRIPTION_MASK_SOURCE;

			pa_subscription_mask_t mask_t = static_cast<pa_subscription_mask_t>(mask);
			pa_operation * op = pa_context_subscribe(context, mask_t, nullptr, nullptr);

			pa_threaded_mainloop_unlock(mainloop);

			if (!op) {
				throw Exception("PulseAudio: Failed to subscribe context");
			}
			pa_operation_unref(op);
		}

		PulseAudioDeviceManager::~PulseAudioDeviceManager()
		{
			dispose();
		}

		void PulseAudioDeviceManager::dispose()
		{
			if (!mainloop) {
				return;
			}

			pa_threaded_mainloop_lock(mainloop);

			if (context) {
				pa_context_set_state_callback(context, nullptr, nullptr);
				pa_context_disconnect(context);
				pa_context_unref(context);
				context = nullptr;
			}

			pa_threaded_mainloop_stop(mainloop);
			pa_threaded_mainloop_free(mainloop);

			mainloop = nullptr;
		}

		void PulseAudioDeviceManager::iterate(pa_threaded_mainloop * main_loop, pa_operation * op) {
			if (!op) {
				throw Exception("PulseAudio: No operation to process");
			}

			while (pa_operation_get_state(op) == PA_OPERATION_RUNNING) {
				pa_threaded_mainloop_wait(main_loop);
			}

			pa_operation_unref(op);
		}

		AudioDevicePtr PulseAudioDeviceManager::getDefaultAudioCaptureDevice()
		{
			if (!pa_threaded_mainloop_in_thread(mainloop))
				pa_threaded_mainloop_lock(mainloop);

			pa_operation * op = pa_context_get_server_info(context, serverInfoCallback, this);

			if (!pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			op = pa_context_get_source_info_by_name(context, defaultCaptureName.c_str(), getSourceInfoCallback, this);

			if (!pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			if (!pa_threaded_mainloop_in_thread(mainloop))
				pa_threaded_mainloop_unlock(mainloop);

			return std::make_shared<AudioDevice>(defaultCaptureDescName, defaultCaptureName);
		}

		std::set<AudioDevicePtr> PulseAudioDeviceManager::getAudioCaptureDevices()
		{
			if (!captureDevices.empty()) {
				return captureDevices.devices();
			}

			pa_threaded_mainloop_lock(mainloop);
			pa_operation * op = pa_context_get_source_info_list(context, getSourceCallback, this);
			iterate(mainloop, op);
			pa_threaded_mainloop_unlock(mainloop);

			return captureDevices.devices();
		}

		AudioDevicePtr PulseAudioDeviceManager::getDefaultAudioPlaybackDevice()
		{
			if (!pa_threaded_mainloop_in_thread(mainloop))
				pa_threaded_mainloop_lock(mainloop);

			pa_operation * op = pa_context_get_server_info(context, serverInfoCallback, this);

			if (!pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			op = pa_context_get_sink_info_by_name(context, defaultPlaybackName.c_str(), getSinkInfoCallback, this);

			if (!pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			if (!pa_threaded_mainloop_in_thread(mainloop))
				pa_threaded_mainloop_unlock(mainloop);

			return std::make_shared<AudioDevice>(defaultPlaybackDescName, defaultPlaybackName);
		}

		std::set<AudioDevicePtr> PulseAudioDeviceManager::getAudioPlaybackDevices()
		{
			if (!playbackDevices.empty()) {
				return playbackDevices.devices();
			}

			pa_threaded_mainloop_lock(mainloop);
			pa_operation * op = pa_context_get_sink_info_list(context, getSinkCallback, this);
			iterate(mainloop, op);
			pa_threaded_mainloop_unlock(mainloop);

			return playbackDevices.devices();
		}

		void PulseAudioDeviceManager::getSourceInfoCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last > 0) {
				pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->defaultCaptureDescName = info->description;
		}

		void PulseAudioDeviceManager::getSourceCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			if (info->monitor_of_sink == PA_INVALID_INDEX) {
				engine->insertDevice(engine->captureDevices, info->description, info->name, info->index, false);
			}
		}

		void PulseAudioDeviceManager::newSourceCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->insertDevice(engine->captureDevices, info->description, info->name, info->index, true);
		}

		void PulseAudioDeviceManager::getSinkInfoCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last > 0) {
				pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->defaultPlaybackDescName = info->description;
		}

		void PulseAudioDeviceManager::getSinkCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->insertDevice(engine->playbackDevices, info->description, info->name, info->index, false);
		}

		void PulseAudioDeviceManager::newSinkCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->insertDevice(engine->playbackDevices, info->description, info->name, info->index, true);
		}

		void PulseAudioDeviceManager::insertDevice(DeviceList<AudioDevicePtr> & devices, const char * name, const char * desc, uint32_t index, bool notify)
		{
			auto device = std::make_shared<AudioDevice>(name, desc);

			if (devices.insertDevice(device)) {
				deviceMap[index] = device;
			}
			if (notify) {
				notifyDeviceConnected(device);
			}
		}

		void PulseAudioDeviceManager::removeDevice(DeviceList<AudioDevicePtr> & devices, uint32_t index)
		{
			auto it = deviceMap.find(index);
			if (it == deviceMap.end()) {
				return;
			}

			if (devices.removeDevice(it->second)) {
				notifyDeviceDisconnected(it->second);
				deviceMap.erase(it);
			}
		}

		void PulseAudioDeviceManager::stateCallback(pa_context * ctx, void * userdata)
		{
			pa_threaded_mainloop * mainloop = static_cast<pa_threaded_mainloop *>(userdata);
			pa_threaded_mainloop_signal(mainloop, 0);
		}

		void PulseAudioDeviceManager::serverInfoCallback(pa_context * ctx, const pa_server_info * info, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);
			engine->defaultCaptureName = info->default_source_name;
			engine->defaultPlaybackName = info->default_sink_name;

			pa_threaded_mainloop_signal(engine->mainloop, 0);
		}

		void PulseAudioDeviceManager::subscribeCallback(pa_context * ctx, pa_subscription_event_type_t type, uint32_t idx, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);
			unsigned facility = type & PA_SUBSCRIPTION_EVENT_FACILITY_MASK;
			unsigned operation = type & PA_SUBSCRIPTION_EVENT_TYPE_MASK;
			pa_operation * op = nullptr;

			if (facility == PA_SUBSCRIPTION_EVENT_SOURCE) {
				if (operation == PA_SUBSCRIPTION_EVENT_NEW) {
					op = pa_context_get_source_info_by_index(ctx, idx, newSourceCallback, engine);
				}
				if (operation == PA_SUBSCRIPTION_EVENT_REMOVE) {
					engine->removeDevice(engine->captureDevices, idx);
				}
			}
			if (facility == PA_SUBSCRIPTION_EVENT_SINK) {
				if (operation == PA_SUBSCRIPTION_EVENT_NEW) {
					op = pa_context_get_sink_info_by_index(ctx, idx, newSinkCallback, engine);
				}
				if (operation == PA_SUBSCRIPTION_EVENT_REMOVE) {
					engine->removeDevice(engine->playbackDevices, idx);
				}
			}

			if (op) {
				pa_operation_unref(op);
			}
		}

	}
}