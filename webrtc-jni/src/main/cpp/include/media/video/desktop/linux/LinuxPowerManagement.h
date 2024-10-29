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

#ifndef JNI_WEBRTC_MEDIA_DESKTOP_LINUX_POWER_MANAGEMENT_H_
#define JNI_WEBRTC_MEDIA_DESKTOP_LINUX_POWER_MANAGEMENT_H_

#include "media/video/desktop/PowerManagement.h"

#include <cstdint>

namespace jni
{
	namespace avdev
	{
		class LinuxPowerManagement : public PowerManagement
		{
			public:
				LinuxPowerManagement();
				~LinuxPowerManagement() = default;

				void enableUserActivity();
				void disableUserActivity();

			private:
				uint32_t dbusCookie = 0;

				// KDE >= 4 and GNOME >= 3.10
				const char * BUS_SERVICE_NAME = "org.freedesktop.ScreenSaver";
				const char * BUS_SERVICE_PATH = "/org/freedesktop/ScreenSaver";
				const char * BUS_INTERFACE = "org.freedesktop.ScreenSaver";

				const char * appName = "WebRTC - Java";
				const char * reason = "Running WebRTC";
		};
	}
}

#endif