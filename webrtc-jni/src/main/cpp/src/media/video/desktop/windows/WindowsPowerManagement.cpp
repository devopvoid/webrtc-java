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

#include "media/video/desktop/windows/WindowsPowerManagement.h"

#include "rtc_base/logging.h"

namespace jni
{
	namespace avdev
	{
		namespace
		{
			constexpr POWER_REQUEST_TYPE kPowerRequestTypes[] = {
				PowerRequestDisplayRequired,
				PowerRequestSystemRequired,
				PowerRequestAwayModeRequired
			};
		}

		WindowsPowerManagement::WindowsPowerManagement()
		{
		}

		void WindowsPowerManagement::enableUserActivity()
		{
			if (powerRequest != nullptr) {
				return;
			}

			wchar_t reason[] = L"WebRTC-Java processing";

			REASON_CONTEXT context = {};
			context.Version = POWER_REQUEST_CONTEXT_VERSION;
			context.Flags = POWER_REQUEST_CONTEXT_SIMPLE_STRING;
			context.Reason.SimpleReasonString = reason;

			HANDLE request = PowerCreateRequest(&context);

			if (request == INVALID_HANDLE_VALUE) {
				RTC_LOG(LS_ERROR) << "[PowerManagement] Cannot create power request: " << GetLastError();
				return;
			}

			for (POWER_REQUEST_TYPE type : kPowerRequestTypes) {
				if (!PowerSetRequest(request, type)) {
					RTC_LOG(LS_WARNING) << "[PowerManagement] Cannot set power request " << static_cast<int>(type) << ": " << GetLastError();
				}
			}

			powerRequest = request;
		}

		void WindowsPowerManagement::disableUserActivity()
        {
			if (powerRequest == nullptr) {
				return;
			}

			for (POWER_REQUEST_TYPE type : kPowerRequestTypes) {
				PowerClearRequest(powerRequest, type);
			}

			CloseHandle(powerRequest);
			powerRequest = nullptr;
        }
	}
}