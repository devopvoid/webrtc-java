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

#include "media/HardwareFault.h"

#include <atomic>

namespace
{
	enum Mode
	{
		kNone,
		kSend,
		kDrain
	};

	std::atomic<int> mode{ kNone };
	std::atomic<int> error_code{ 0 };
	std::atomic<int> packets_left{ 0 };
	std::atomic<int> software_threads{ 0 };
}

namespace ffmpeg
{
	void HardwareFault::FailSend(int error, int after_packets)
	{
		error_code.store(error);
		packets_left.store(after_packets);
		mode.store(kSend);
	}

	void HardwareFault::FailDrain(int error)
	{
		error_code.store(error);
		mode.store(kDrain);
	}

	void HardwareFault::Disarm()
	{
		mode.store(kNone);
		software_threads.store(0);
	}

	void HardwareFault::NoteSoftwareDecoder(int threads)
	{
		software_threads.store(threads);
	}

	int HardwareFault::SoftwareThreads()
	{
		return software_threads.load();
	}

	int HardwareFault::NextSendError()
	{
		if (mode.load() != kSend) {
			return 0;
		}

		if (packets_left.fetch_sub(1) > 0) {
			return 0;
		}

		// Once only: the decoder that takes over must not meet it again.
		mode.store(kNone);

		return error_code.load();
	}

	bool HardwareFault::HoldsPictures()
	{
		return mode.load() == kDrain;
	}

	int HardwareFault::DrainError()
	{
		mode.store(kNone);

		return error_code.load();
	}
}
