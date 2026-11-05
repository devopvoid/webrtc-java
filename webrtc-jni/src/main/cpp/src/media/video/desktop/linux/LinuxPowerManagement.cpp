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
#include "media/video/desktop/linux/DBusLoader.h"

#include <cstdio>
#include <dbus/dbus.h>
#include <memory>

#include "rtc_base/logging.h"

namespace jni
{
	namespace avdev
	{
		namespace
		{
			struct ConnectionUnref
			{
				void operator()(DBusConnection * connection) const
				{
					DBusLoader::instance().dbus_connection_unref(connection);
				}
			};

			struct MessageUnref
			{
				void operator()(DBusMessage * message) const
				{
					DBusLoader::instance().dbus_message_unref(message);
				}
			};

			using ScopedConnection = std::unique_ptr<DBusConnection, ConnectionUnref>;
			using ScopedMessage = std::unique_ptr<DBusMessage, MessageUnref>;
		}

		LinuxPowerManagement::LinuxPowerManagement()
		{
		}

		void LinuxPowerManagement::enableUserActivity()
		{
			if (inhibited) {
				return;
			}

			if (!DBusLoader::instance().load()) {
				RTC_LOG(LS_WARNING) << "[PowerManagement] libdbus not found on system";
				return;
			}

			auto & dbus = DBusLoader::instance();

			DBusError error;
			dbus.dbus_error_init(&error);

			ScopedConnection dbusConnection(dbus.dbus_bus_get(DBUS_BUS_SESSION, &error));

			if (dbus.dbus_error_is_set(&error)) {
				// throw
				RTC_LOG(LS_ERROR) << "[PowerManagement] Cannot connect to session bus: " << error.message;
				dbus.dbus_error_free(&error);
				return;
			}

			if (dbus.dbus_bus_name_has_owner(dbusConnection.get(), BUS_SERVICE_NAME, NULL)) {
				RTC_LOG(LS_INFO) << "[PowerManagement] Found service: " << BUS_SERVICE_NAME;
			}
			else {
				// throw
				RTC_LOG(LS_WARNING) << "[PowerManagement] Cannot find service: " << BUS_SERVICE_NAME;
				return;
			}

			ScopedMessage message(dbus.dbus_message_new_method_call(BUS_SERVICE_NAME, BUS_SERVICE_PATH, BUS_INTERFACE,
				"Inhibit"));

			if (message == nullptr) {
				// throw
				return;
			}

			dbus.dbus_message_append_args(message.get(), DBUS_TYPE_STRING, &appName, DBUS_TYPE_STRING, &reason, DBUS_TYPE_INVALID);

			ScopedMessage reply(dbus.dbus_connection_send_with_reply_and_block(dbusConnection.get(), message.get(), 50, &error));

			if (dbus.dbus_error_is_set(&error)) {
				dbus.dbus_error_free(&error);
				// throw
				RTC_LOG(LS_ERROR) << "[PowerManagement] Cannot retrieve cookie";
				return;
			}

			DBusMessageIter reply_iter;
			dbus.dbus_message_iter_init(reply.get(), &reply_iter);
			dbus.dbus_message_iter_get_basic(&reply_iter, &dbusCookie);
			inhibited = true;

			RTC_LOG(LS_INFO) << "[PowerManagement] Acquired screensaver inhibition cookie";
		}

		void LinuxPowerManagement::disableUserActivity()
        {
			if (!inhibited) {
				return;
			}

			if (!DBusLoader::instance().load()) {
				RTC_LOG(LS_WARNING) << "[PowerManagement] libdbus not found on system";
				return;
			}

			auto & dbus = DBusLoader::instance();

			DBusError error;
			dbus.dbus_error_init(&error);

			ScopedConnection dbusConnection(dbus.dbus_bus_get(DBUS_BUS_SESSION, &error));

			if (dbus.dbus_error_is_set(&error)) {
				// throw
				RTC_LOG(LS_ERROR) << "[PowerManagement] Cannot connect to session bus: " << error.message;
				dbus.dbus_error_free(&error);
				return;
			}

			ScopedMessage message(dbus.dbus_message_new_method_call(BUS_SERVICE_NAME, BUS_SERVICE_PATH, BUS_INTERFACE,
				"UnInhibit"));

			if (message == nullptr) {
				// throw
				return;
			}

			dbus.dbus_message_append_args(message.get(), DBUS_TYPE_UINT32, &dbusCookie, DBUS_TYPE_INVALID);

			ScopedMessage reply(dbus.dbus_connection_send_with_reply_and_block(dbusConnection.get(), message.get(), 50, &error));

			if (dbus.dbus_error_is_set(&error)) {
				// throw
				RTC_LOG(LS_ERROR) << "[PowerManagement] Cannot release cookie";

				dbus.dbus_error_free(&error);
				return;
			}

			inhibited = false;

			RTC_LOG(LS_INFO) << "[PowerManagement] Released screensaver inhibition cookie";
        }
	}
}