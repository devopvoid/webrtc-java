/*
 * Copyright 2026 Alex Andres
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

#include "media/WriteFault.h"

#include <atomic>

namespace
{
	constexpr int kNone = -1;

	std::atomic<int> armed_call{ kNone };
	std::atomic<int> error_code{ 0 };
	std::atomic<int> calls_left{ 0 };
}

namespace ffmpeg
{
	void WriteFault::Fail(Call call, int error, int after_calls)
	{
		error_code.store(error);
		calls_left.store(after_calls);
		armed_call.store(static_cast<int>(call));
	}

	void WriteFault::Disarm()
	{
		armed_call.store(kNone);
	}

	int WriteFault::Check(Call call, int result)
	{
		int expected = static_cast<int>(call);

		if (result < 0 || armed_call.load() != expected) {
			return result;
		}
		if (calls_left.fetch_sub(1) > 0) {
			return result;
		}

		// Once only, for whichever call gets here first.
		if (!armed_call.compare_exchange_strong(expected, kNone)) {
			return result;
		}

		return error_code.load();
	}
}
