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


#ifndef JNI_WEBRTC_MEDIA_V4L2_UDEV_LOADER_H_
#define JNI_WEBRTC_MEDIA_V4L2_UDEV_LOADER_H_

#include <libudev.h>

#include <dlfcn.h>

namespace jni
{
	namespace avdev
	{
		typedef struct udev * (*udev_new_t)();
		typedef struct udev * (*udev_unref_t)(struct udev * udev);
		typedef struct udev_enumerate * (*udev_enumerate_new_t)(struct udev * udev);
		typedef struct udev_enumerate * (*udev_enumerate_unref_t)(struct udev_enumerate * enumerate);
		typedef int (*udev_enumerate_add_match_subsystem_t)(struct udev_enumerate * enumerate, const char * subsystem);
		typedef int (*udev_enumerate_scan_devices_t)(struct udev_enumerate * enumerate);
		typedef struct udev_list_entry * (*udev_enumerate_get_list_entry_t)(struct udev_enumerate * enumerate);
		typedef struct udev_list_entry * (*udev_list_entry_get_next_t)(struct udev_list_entry * entry);
		typedef const char * (*udev_list_entry_get_name_t)(struct udev_list_entry * entry);
		typedef struct udev_device * (*udev_device_new_from_syspath_t)(struct udev * udev, const char * syspath);
		typedef struct udev_device * (*udev_device_unref_t)(struct udev_device * device);
		typedef const char * (*udev_device_get_devnode_t)(struct udev_device * device);
		typedef const char * (*udev_device_get_subsystem_t)(struct udev_device * device);
		typedef const char * (*udev_device_get_action_t)(struct udev_device * device);
		typedef const char * (*udev_device_get_property_value_t)(struct udev_device * device, const char * key);
		typedef struct udev_monitor * (*udev_monitor_new_from_netlink_t)(struct udev * udev, const char * name);
		typedef struct udev_monitor * (*udev_monitor_unref_t)(struct udev_monitor * monitor);
		typedef int (*udev_monitor_filter_add_match_subsystem_devtype_t)(struct udev_monitor * monitor, const char * subsystem, const char * devtype);
		typedef int (*udev_monitor_enable_receiving_t)(struct udev_monitor * monitor);
		typedef int (*udev_monitor_get_fd_t)(struct udev_monitor * monitor);
		typedef struct udev_device * (*udev_monitor_receive_device_t)(struct udev_monitor * monitor);

		class UdevLoader
		{
			public:
				static UdevLoader & instance()
				{
					static UdevLoader instance;
					return instance;
				}

				bool load()
				{
					if (loaded) {
						return true;
					}

					handle = dlopen("libudev.so.1", RTLD_NOW);

					if (!handle) {
						return false;
					}

					#define UDEV_LOAD_SYM(name) \
						name = (name##_t) dlsym(handle, #name); \
						if (!name) { close(); return false; }

					UDEV_LOAD_SYM(udev_new);
					UDEV_LOAD_SYM(udev_unref);
					UDEV_LOAD_SYM(udev_enumerate_new);
					UDEV_LOAD_SYM(udev_enumerate_unref);
					UDEV_LOAD_SYM(udev_enumerate_add_match_subsystem);
					UDEV_LOAD_SYM(udev_enumerate_scan_devices);
					UDEV_LOAD_SYM(udev_enumerate_get_list_entry);
					UDEV_LOAD_SYM(udev_list_entry_get_next);
					UDEV_LOAD_SYM(udev_list_entry_get_name);
					UDEV_LOAD_SYM(udev_device_new_from_syspath);
					UDEV_LOAD_SYM(udev_device_unref);
					UDEV_LOAD_SYM(udev_device_get_devnode);
					UDEV_LOAD_SYM(udev_device_get_subsystem);
					UDEV_LOAD_SYM(udev_device_get_action);
					UDEV_LOAD_SYM(udev_device_get_property_value);
					UDEV_LOAD_SYM(udev_monitor_new_from_netlink);
					UDEV_LOAD_SYM(udev_monitor_unref);
					UDEV_LOAD_SYM(udev_monitor_filter_add_match_subsystem_devtype);
					UDEV_LOAD_SYM(udev_monitor_enable_receiving);
					UDEV_LOAD_SYM(udev_monitor_get_fd);
					UDEV_LOAD_SYM(udev_monitor_receive_device);

					#undef UDEV_LOAD_SYM

					loaded = true;

					return true;
				}

				void close()
				{
					if (handle) {
						dlclose(handle);
						handle = nullptr;
					}

					loaded = false;
				}

				bool isLoaded() const
				{
					return loaded;
				}

				udev_new_t udev_new;
				udev_unref_t udev_unref;
				udev_enumerate_new_t udev_enumerate_new;
				udev_enumerate_unref_t udev_enumerate_unref;
				udev_enumerate_add_match_subsystem_t udev_enumerate_add_match_subsystem;
				udev_enumerate_scan_devices_t udev_enumerate_scan_devices;
				udev_enumerate_get_list_entry_t udev_enumerate_get_list_entry;
				udev_list_entry_get_next_t udev_list_entry_get_next;
				udev_list_entry_get_name_t udev_list_entry_get_name;
				udev_device_new_from_syspath_t udev_device_new_from_syspath;
				udev_device_unref_t udev_device_unref;
				udev_device_get_devnode_t udev_device_get_devnode;
				udev_device_get_subsystem_t udev_device_get_subsystem;
				udev_device_get_action_t udev_device_get_action;
				udev_device_get_property_value_t udev_device_get_property_value;
				udev_monitor_new_from_netlink_t udev_monitor_new_from_netlink;
				udev_monitor_unref_t udev_monitor_unref;
				udev_monitor_filter_add_match_subsystem_devtype_t udev_monitor_filter_add_match_subsystem_devtype;
				udev_monitor_enable_receiving_t udev_monitor_enable_receiving;
				udev_monitor_get_fd_t udev_monitor_get_fd;
				udev_monitor_receive_device_t udev_monitor_receive_device;

			private:
				UdevLoader() : loaded(false), handle(nullptr) {}

				bool loaded;
				void * handle;
		};
	}
}

#endif
