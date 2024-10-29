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

#include "media/video/desktop/linux/LinuxPowerManagement.h"

#include <cstdio>
#include <dbus/dbus.h>

namespace jni
{
	namespace avdev
	{
		LinuxPowerManagement::LinuxPowerManagement()
		{
		}

		void LinuxPowerManagement::enableUserActivity()
		{
			DBusError error;
			dbus_error_init(&error);

			DBusConnection * dbusConnection = dbus_bus_get(DBUS_BUS_SESSION, &error);

			if (dbus_error_is_set(&error)) {
				// throw
				printf("[PowerManagement] Cannot connect to session bus: %s\n", error.message);
				dbus_error_free(&error);
				return;
			}

			if (dbus_bus_name_has_owner(dbusConnection, BUS_SERVICE_NAME, NULL)) {
				printf("[PowerManagement] Found service %s\n", BUS_SERVICE_NAME);
			}
			else {
				// throw
				printf("[PowerManagement] Cannot find service %s\n", BUS_SERVICE_NAME);
				return;
			}

			DBusMessage * message = dbus_message_new_method_call(BUS_SERVICE_NAME, BUS_SERVICE_PATH, BUS_INTERFACE,
				"Inhibit");

			if (message == nullptr) {
				// throw
				return;
			}

			dbus_message_append_args(message, DBUS_TYPE_STRING, &appName, DBUS_TYPE_STRING, &reason, DBUS_TYPE_INVALID);

			DBusMessage * reply = dbus_connection_send_with_reply_and_block(dbusConnection, message, 50, &error);
			dbus_message_unref(message);

			if (dbus_error_is_set(&error)) {
				dbus_error_free(&error);
				dbus_connection_unref(dbusConnection);
				// throw
                printf("[PowerManagement] Cannot retrieve cookie\n");
				return;
			}

			DBusMessageIter reply_iter;
			dbus_message_iter_init(reply, &reply_iter);
			dbus_message_iter_get_basic(&reply_iter, &dbusCookie);

			printf("[PowerManagement] Acquired screensaver inhibition cookie\n");

			dbus_message_unref(reply);
			dbus_connection_unref(dbusConnection);
		}

		void LinuxPowerManagement::disableUserActivity()
        {
			DBusError error;
			dbus_error_init(&error);

			DBusConnection * dbusConnection = dbus_bus_get(DBUS_BUS_SESSION, &error);

			if (dbus_error_is_set(&error)) {
				// throw
				printf("[PowerManagement] Cannot connect to session bus: %s\n", error.message);
				dbus_error_free(&error);
				return;
			}

			DBusMessage * message = dbus_message_new_method_call(BUS_SERVICE_NAME, BUS_SERVICE_PATH, BUS_INTERFACE,
				"UnInhibit");

			if (message == nullptr) {
				// throw
				return;
			}

			dbus_message_append_args(message, DBUS_TYPE_UINT32, &dbusCookie, DBUS_TYPE_INVALID);

			DBusMessage * reply = dbus_connection_send_with_reply_and_block(dbusConnection, message, 50, &error);
			dbus_message_unref(message);

			if (dbus_error_is_set(&error)) {
				// throw
				printf("[PowerManagement] Cannot release cookie\n");

				dbus_error_free(&error);
				dbus_connection_unref(dbusConnection);
				return;
			}

			printf("[PowerManagement] Released screensaver inhibition cookie\n");

			dbus_message_unref(reply);
			dbus_connection_unref(dbusConnection);
        }
	}
}