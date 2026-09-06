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


#ifndef JNI_WEBRTC_MEDIA_DESKTOP_LINUX_DBUS_LOADER_H_
#define JNI_WEBRTC_MEDIA_DESKTOP_LINUX_DBUS_LOADER_H_

#include <dbus/dbus.h>

#include <dlfcn.h>

namespace jni
{
	namespace avdev
	{
		typedef void (*dbus_error_init_t)(DBusError * error);
		typedef void (*dbus_error_free_t)(DBusError * error);
		typedef dbus_bool_t (*dbus_error_is_set_t)(const DBusError * error);
		typedef DBusConnection * (*dbus_bus_get_t)(DBusBusType type, DBusError * error);
		typedef dbus_bool_t (*dbus_bus_name_has_owner_t)(DBusConnection * connection, const char * name, DBusError * error);
		typedef void (*dbus_connection_unref_t)(DBusConnection * connection);
		typedef DBusMessage * (*dbus_connection_send_with_reply_and_block_t)(DBusConnection * connection, DBusMessage * message, int timeout, DBusError * error);
		typedef DBusMessage * (*dbus_message_new_method_call_t)(const char * destination, const char * path, const char * iface, const char * method);
		typedef dbus_bool_t (*dbus_message_append_args_t)(DBusMessage * message, int firstArgType, ...);
		typedef void (*dbus_message_unref_t)(DBusMessage * message);
		typedef dbus_bool_t (*dbus_message_iter_init_t)(DBusMessage * message, DBusMessageIter * iter);
		typedef void (*dbus_message_iter_get_basic_t)(DBusMessageIter * iter, void * value);

		class DBusLoader
		{
			public:
				static DBusLoader & instance()
				{
					static DBusLoader instance;
					return instance;
				}

				bool load()
				{
					if (loaded) {
						return true;
					}

					handle = dlopen("libdbus-1.so.3", RTLD_NOW);

					if (!handle) {
						return false;
					}

					#define DBUS_LOAD_SYM(name) \
						name = (name##_t) dlsym(handle, #name); \
						if (!name) { close(); return false; }

					DBUS_LOAD_SYM(dbus_error_init);
					DBUS_LOAD_SYM(dbus_error_free);
					DBUS_LOAD_SYM(dbus_error_is_set);
					DBUS_LOAD_SYM(dbus_bus_get);
					DBUS_LOAD_SYM(dbus_bus_name_has_owner);
					DBUS_LOAD_SYM(dbus_connection_unref);
					DBUS_LOAD_SYM(dbus_connection_send_with_reply_and_block);
					DBUS_LOAD_SYM(dbus_message_new_method_call);
					DBUS_LOAD_SYM(dbus_message_append_args);
					DBUS_LOAD_SYM(dbus_message_unref);
					DBUS_LOAD_SYM(dbus_message_iter_init);
					DBUS_LOAD_SYM(dbus_message_iter_get_basic);

					#undef DBUS_LOAD_SYM

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

				dbus_error_init_t dbus_error_init;
				dbus_error_free_t dbus_error_free;
				dbus_error_is_set_t dbus_error_is_set;
				dbus_bus_get_t dbus_bus_get;
				dbus_bus_name_has_owner_t dbus_bus_name_has_owner;
				dbus_connection_unref_t dbus_connection_unref;
				dbus_connection_send_with_reply_and_block_t dbus_connection_send_with_reply_and_block;
				dbus_message_new_method_call_t dbus_message_new_method_call;
				dbus_message_append_args_t dbus_message_append_args;
				dbus_message_unref_t dbus_message_unref;
				dbus_message_iter_init_t dbus_message_iter_init;
				dbus_message_iter_get_basic_t dbus_message_iter_get_basic;

			private:
				DBusLoader() : loaded(false), handle(nullptr) {}

				bool loaded;
				void * handle;
		};
	}
}

#endif
