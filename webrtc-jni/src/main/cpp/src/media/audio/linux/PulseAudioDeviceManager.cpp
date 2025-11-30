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
#include "media/audio/linux/PulseAudioLoader.h"
#include "Exception.h"

namespace jni
{
	namespace avdev
	{
		PulseAudioDeviceManager::PulseAudioDeviceManager()
		{
            // Try to load PulseAudio symbols dynamically
            if (!PulseAudioLoader::instance().load()) {
                throw Exception("PulseAudio: Library not found on system.");
            }

            // Use the loader instance for function calls
            auto& pa = PulseAudioLoader::instance();

			mainloop = pa.pa_threaded_mainloop_new();

			if (mainloop == 0) {
				throw Exception("PulseAudio: Could not create threaded mainloop");
			}

			if (pa.pa_threaded_mainloop_start(mainloop) != 0) {
				pa.pa_threaded_mainloop_free(mainloop);

				throw Exception("PulseAudio: Could not start threaded mainloop");
			}

			pa_mainloop_api * mainloopApi = pa.pa_threaded_mainloop_get_api(mainloop);
			context = pa.pa_context_new(mainloopApi, "MediaDevices");

			if (!context) {
				pa.pa_threaded_mainloop_free(mainloop);

				throw Exception("PulseAudio: Could not create context");
			}

			pa.pa_context_set_state_callback(context, stateCallback, mainloop);
			pa.pa_context_set_subscribe_callback(context, subscribeCallback, this);

			pa.pa_threaded_mainloop_lock(mainloop);

			if (pa.pa_context_connect(context, nullptr, PA_CONTEXT_NOFLAGS, nullptr) < 0) {
				pa.pa_context_unref(context);
				pa.pa_threaded_mainloop_free(mainloop);

				throw Exception("PulseAudio: Could not connect to the context");
			}

			while (true) {
				pa_context_state_t contextState = pa.pa_context_get_state(context);

				if (contextState == PA_CONTEXT_FAILED || contextState == PA_CONTEXT_TERMINATED) {
					dispose();
					return;
				}
				if (contextState == PA_CONTEXT_READY) {
					break;
				}

				pa.pa_threaded_mainloop_wait(mainloop);
			}

			int mask = PA_SUBSCRIPTION_MASK_SINK | PA_SUBSCRIPTION_MASK_SOURCE;

			pa_subscription_mask_t mask_t = static_cast<pa_subscription_mask_t>(mask);
			pa_operation * op = pa.pa_context_subscribe(context, mask_t, nullptr, nullptr);

			pa.pa_threaded_mainloop_unlock(mainloop);

			if (!op) {
				throw Exception("PulseAudio: Failed to subscribe context");
			}
			pa.pa_operation_unref(op);
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

			pa.pa_threaded_mainloop_lock(mainloop);

			if (context) {
				pa.pa_context_set_state_callback(context, nullptr, nullptr);
				pa.pa_context_disconnect(context);
				pa.pa_context_unref(context);
				context = nullptr;
			}

			pa.pa_threaded_mainloop_stop(mainloop);
			pa.pa_threaded_mainloop_free(mainloop);

			mainloop = nullptr;
		}

		void PulseAudioDeviceManager::iterate(pa_threaded_mainloop * main_loop, pa_operation * op) {
			if (!op) {
				throw Exception("PulseAudio: No operation to process");
			}

			while (pa.pa_operation_get_state(op) == PA_OPERATION_RUNNING) {
				pa.pa_threaded_mainloop_wait(main_loop);
			}

			pa.pa_operation_unref(op);
		}

		AudioDevicePtr PulseAudioDeviceManager::getDefaultAudioCaptureDevice()
		{
			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				pa.pa_threaded_mainloop_lock(mainloop);

			pa_operation * op = pa.pa_context_get_server_info(context, serverInfoCallback, this);

			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			op = pa.pa_context_get_source_info_by_name(context, defaultCaptureName.c_str(), getSourceInfoCallback, this);

			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				pa.pa_threaded_mainloop_unlock(mainloop);

            AudioDevicePtr defaultDevice = std::make_shared<AudioDevice>(defaultCaptureDescName, defaultCaptureName);
			defaultDevice->directionType = AudioDeviceDirectionType::adtCapture;
			return defaultDevice;
		}

		std::set<AudioDevicePtr> PulseAudioDeviceManager::getAudioCaptureDevices()
		{
			if (!captureDevices.empty()) {
				return captureDevices.devices();
			}

			pa.pa_threaded_mainloop_lock(mainloop);
			pa_operation * op = pa.pa_context_get_source_info_list(context, getSourceCallback, this);
			iterate(mainloop, op);
			pa.pa_threaded_mainloop_unlock(mainloop);

			return captureDevices.devices();
		}

		AudioDevicePtr PulseAudioDeviceManager::getDefaultAudioPlaybackDevice()
		{
			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				pa.pa_threaded_mainloop_lock(mainloop);

			pa_operation * op = pa.pa_context_get_server_info(context, serverInfoCallback, this);

			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			op = pa.pa_context_get_sink_info_by_name(context, defaultPlaybackName.c_str(), getSinkInfoCallback, this);

			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				iterate(mainloop, op);

			if (!pa.pa_threaded_mainloop_in_thread(mainloop))
				pa.pa_threaded_mainloop_unlock(mainloop);

            AudioDevicePtr defaultDevice = std::make_shared<AudioDevice>(defaultPlaybackDescName, defaultPlaybackName);
            defaultDevice->directionType = AudioDeviceDirectionType::adtRender;
            return defaultDevice;
		}

		std::set<AudioDevicePtr> PulseAudioDeviceManager::getAudioPlaybackDevices()
		{
			if (!playbackDevices.empty()) {
				return playbackDevices.devices();
			}

			pa.pa_threaded_mainloop_lock(mainloop);
			pa_operation * op = pa.pa_context_get_sink_info_list(context, getSinkCallback, this);
			iterate(mainloop, op);
			pa.pa_threaded_mainloop_unlock(mainloop);

			return playbackDevices.devices();
		}

		void PulseAudioDeviceManager::getSourceInfoCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last > 0) {
				pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->defaultCaptureDescName = info->description;
		}

		void PulseAudioDeviceManager::getSourceCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			if (info->monitor_of_sink == PA_INVALID_INDEX) {
				engine->insertDevice(engine->captureDevices, info->proplist, info->description, info->name, info->index, false, true);
			}
		}

		void PulseAudioDeviceManager::newSourceCallback(pa_context * ctx, const pa_source_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->insertDevice(engine->captureDevices, info->proplist, info->description, info->name, info->index, true, true);
		}

		void PulseAudioDeviceManager::getSinkInfoCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last > 0) {
				pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->defaultPlaybackDescName = info->description;
		}

		void PulseAudioDeviceManager::getSinkCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->insertDevice(engine->playbackDevices, info->proplist, info->description, info->name, info->index, false, false);
		}

		void PulseAudioDeviceManager::newSinkCallback(pa_context * ctx, const pa_sink_info * info, int last, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);

			if (last) {
				pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
				return;
			}

			engine->insertDevice(engine->playbackDevices, info->proplist, info->description, info->name, info->index, true, false);
		}

		void PulseAudioDeviceManager::insertDevice(DeviceList<AudioDevicePtr> & devices, pa_proplist * proplist, const char * name, const char * desc, uint32_t index, bool notify, bool isCapture)
		{
			auto device = std::make_shared<AudioDevice>(name, desc);

			if (devices.insertDevice(device)) {
			    if (isCapture) {
                    device->directionType = AudioDeviceDirectionType::adtCapture;
                } else {
                    device->directionType = AudioDeviceDirectionType::adtRender;
                }
                fillAdditionalTypes(device, proplist);
				deviceMap[index] = device;
			}
			if (notify) {
				notifyDeviceConnected(device);
			}
		}

		void PulseAudioDeviceManager::removeDevice(DeviceList<AudioDevicePtr> & devices, uint32_t index, bool isCapture)
		{
			auto it = deviceMap.find(index);
			if (it == deviceMap.end()) {
				return;
			}

			if (devices.removeDevice(it->second)) {
			    if (isCapture) {
			        it->second->directionType = AudioDeviceDirectionType::adtCapture;
			    } else {
			        it->second->directionType = AudioDeviceDirectionType::adtRender;
			    }

				notifyDeviceDisconnected(it->second);
				deviceMap.erase(it);
			}
		}

		void PulseAudioDeviceManager::stateCallback(pa_context * ctx, void * userdata)
		{
			pa_threaded_mainloop * mainloop = static_cast<pa_threaded_mainloop *>(userdata);
			pa.pa_threaded_mainloop_signal(mainloop, 0);
		}

		void PulseAudioDeviceManager::serverInfoCallback(pa_context * ctx, const pa_server_info * info, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);
			engine->defaultCaptureName = info->default_source_name;
			engine->defaultPlaybackName = info->default_sink_name;

			pa.pa_threaded_mainloop_signal(engine->mainloop, 0);
		}

		void PulseAudioDeviceManager::subscribeCallback(pa_context * ctx, pa_subscription_event_type_t type, uint32_t idx, void * userdata)
		{
			PulseAudioDeviceManager * engine = reinterpret_cast<PulseAudioDeviceManager *>(userdata);
			unsigned facility = type & PA_SUBSCRIPTION_EVENT_FACILITY_MASK;
			unsigned operation = type & PA_SUBSCRIPTION_EVENT_TYPE_MASK;
			pa_operation * op = nullptr;

			if (facility == PA_SUBSCRIPTION_EVENT_SOURCE) {
				if (operation == PA_SUBSCRIPTION_EVENT_NEW) {
					op = pa.pa_context_get_source_info_by_index(ctx, idx, newSourceCallback, engine);
				}
				if (operation == PA_SUBSCRIPTION_EVENT_REMOVE) {
					engine->removeDevice(engine->captureDevices, idx, true);
				}
			}
			if (facility == PA_SUBSCRIPTION_EVENT_SINK) {
				if (operation == PA_SUBSCRIPTION_EVENT_NEW) {
					op = pa.pa_context_get_sink_info_by_index(ctx, idx, newSinkCallback, engine);
				}
				if (operation == PA_SUBSCRIPTION_EVENT_REMOVE) {
					engine->removeDevice(engine->playbackDevices, idx, false);
				}
			}

			if (op) {
				pa.pa_operation_unref(op);
			}
		}

        void PulseAudioDeviceManager::fillAdditionalTypes(AudioDevicePtr device, pa_proplist * proplist) {
            // all property values see here https://docs.rs/libpulse-sys/latest/libpulse_sys/proplist/
            const char *formFactor;
            formFactor = pa.pa_proplist_gets(proplist, PA_PROP_DEVICE_FORM_FACTOR);
            std::string formFactorStr = "";
            if (formFactor) {
                formFactorStr = std::string(formFactor);
            }

            const char *deviceTransport;
            deviceTransport = pa.pa_proplist_gets(proplist, PA_PROP_DEVICE_BUS);
            std::string deviceTransportStr = "";
            if (deviceTransport) {
                deviceTransportStr = std::string(deviceTransport);
            }

            const char *propHDMI;
            propHDMI = pa.pa_proplist_gets(proplist, PA_PROP_DEVICE_BUS_PATH);
            if (propHDMI && std::string(propHDMI).find("_hdmi") != std::string::npos) {
                deviceTransportStr = "hdmi";
            }

            device->setDeviceFormFactor(getActualFormFactor(formFactorStr));
            device->setDeviceTransport(getActualTransport(deviceTransportStr));
        }

        DeviceFormFactor PulseAudioDeviceManager::getActualFormFactor(std::string formFactor) {
            if (formFactor == "speaker") {
                return DeviceFormFactor::ffSpeaker;
            } else  if (formFactor == "microphone") {
                return DeviceFormFactor::ffMicrophone;
            } else  if (formFactor == "headset") {
                return DeviceFormFactor::ffHeadset;
            } else  if (formFactor == "headphone") {
                return DeviceFormFactor::ffHeadphone;
            }

            return DeviceFormFactor::ffUnknown;
        }

        DeviceTransport PulseAudioDeviceManager::getActualTransport(std::string transport) {
            if (transport == "usb") {
                return DeviceTransport::trUsb;
            } else if (transport == "bluetooth") {
                return DeviceTransport::trWireless;
            } else if (transport == "hdmi") {
                return DeviceTransport::trHdmi;
            }

            return DeviceTransport::trUnknown;
        }
	}
}