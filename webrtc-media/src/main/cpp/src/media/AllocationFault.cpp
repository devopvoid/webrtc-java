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

#include "media/AllocationFault.h"

#include <atomic>
#include <new>
#include <system_error>

namespace
{
	std::atomic<bool> fail_string_copy{ false };
	std::atomic<bool> fail_allocation{ false };
	std::atomic<bool> fail_thread_start{ false };
}

namespace ffmpeg
{
	void AllocationFault::FailNextStringCopy()
	{
		fail_string_copy.store(true);
	}

	void AllocationFault::FailNextAllocation()
	{
		fail_allocation.store(true);
	}

	void AllocationFault::FailNextThreadStart()
	{
		fail_thread_start.store(true);
	}

	void AllocationFault::Disarm()
	{
		fail_string_copy.store(false);
		fail_allocation.store(false);
		fail_thread_start.store(false);
	}

	bool AllocationFault::IsArmed()
	{
		return fail_string_copy.load() || fail_allocation.load() || fail_thread_start.load();
	}

	void AllocationFault::CheckStringCopy()
	{
		if (fail_string_copy.load() && fail_string_copy.exchange(false)) {
			throw std::bad_alloc();
		}
	}

	void AllocationFault::CheckAllocation()
	{
		if (fail_allocation.load() && fail_allocation.exchange(false)) {
			throw std::bad_alloc();
		}
	}

	void AllocationFault::CheckThreadStart()
	{
		if (fail_thread_start.load() && fail_thread_start.exchange(false)) {
			throw std::system_error(std::make_error_code(std::errc::resource_unavailable_try_again),
					"Starting a thread failed");
		}
	}
}
