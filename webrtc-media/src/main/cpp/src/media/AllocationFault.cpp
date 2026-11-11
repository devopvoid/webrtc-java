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
#include <cerrno>
#include <new>
#include <system_error>

extern "C" {
#include <libavutil/error.h>
}

namespace
{
	std::atomic<bool> fail_decoder_allocation{ false };
	std::atomic<int> video_failures{ 0 };
	std::atomic<int> audio_failures{ 0 };
	std::atomic<bool> fail_string_copy{ false };
	std::atomic<bool> fail_allocation{ false };
	std::atomic<bool> fail_thread_start{ false };
	std::atomic<bool> fail_writer_allocation{ false };
	std::atomic<int> writer_frames_left{ 0 };
	std::atomic<bool> fail_option{ false };
	std::atomic<bool> fail_observer_add{ false };
	std::atomic<int> observers_left{ 0 };
	std::atomic<bool> fail_queue_room{ false };
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

	void AllocationFault::FailWriterAllocation(int after_frames)
	{
		writer_frames_left.store(after_frames);
		fail_writer_allocation.store(true);
	}

	void AllocationFault::FailNextOption()
	{
		fail_option.store(true);
	}

	void AllocationFault::FailObserverAdd(int after_tracks)
	{
		observers_left.store(after_tracks);
		fail_observer_add.store(true);
	}

	void AllocationFault::FailNextQueueRoom()
	{
		fail_queue_room.store(true);
	}

	void AllocationFault::FailDecoderAllocation()
	{
		fail_decoder_allocation.store(true);
	}

	void AllocationFault::CheckDecoderAllocation()
	{
		if (fail_decoder_allocation.exchange(false)) {
			throw std::bad_alloc();
		}
	}

	void AllocationFault::FailOutput(bool video, int count)
	{
		(video ? video_failures : audio_failures).store(count);
	}

	bool AllocationFault::CheckOutputFailure(bool video)
	{
		auto & remaining = video ? video_failures : audio_failures;
		const int count = remaining.load();
		if (count > 0) {
			remaining.fetch_sub(1);
		}
		return count != 0;
	}

	void AllocationFault::Disarm()
	{
		fail_decoder_allocation.store(false);
		video_failures.store(0);
		audio_failures.store(0);
		fail_string_copy.store(false);
		fail_allocation.store(false);
		fail_thread_start.store(false);
		fail_writer_allocation.store(false);
		fail_option.store(false);
		fail_observer_add.store(false);
		fail_queue_room.store(false);
	}

	bool AllocationFault::IsArmed()
	{
		return fail_decoder_allocation.load() || video_failures.load() != 0
				|| audio_failures.load() != 0 || fail_string_copy.load() || fail_allocation.load() || fail_thread_start.load()
				|| fail_writer_allocation.load() || fail_option.load() || fail_observer_add.load()
				|| fail_queue_room.load();
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

	void AllocationFault::CheckWriterAllocation()
	{
		if (!fail_writer_allocation.load() || writer_frames_left.fetch_sub(1) > 0) {
			return;
		}
		if (fail_writer_allocation.exchange(false)) {
			throw std::bad_alloc();
		}
	}

	int AllocationFault::CheckOption(int result)
	{
		if (fail_option.load() && fail_option.exchange(false)) {
			return AVERROR(ENOMEM);
		}

		return result;
	}

	bool AllocationFault::CheckObserverAdd()
	{
		if (!fail_observer_add.load() || observers_left.fetch_sub(1) > 0) {
			return false;
		}

		return fail_observer_add.exchange(false);
	}

	bool AllocationFault::CheckQueueRoom()
	{
		return fail_queue_room.load() && fail_queue_room.exchange(false);
	}
}
