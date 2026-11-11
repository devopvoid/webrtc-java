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

#include "media/DecoderFault.h"

#include <algorithm>
#include <atomic>
#include <vector>

extern "C" {
#include <libavutil/error.h>
}

namespace
{
	std::atomic<int> mode{ 0 };
	bool target_video = true;
	std::vector<uint8_t> rejected;
	int64_t rejected_pts = 0;
	std::atomic<int> retries{ 0 };
}

namespace ffmpeg
{
	void DecoderFault::Arm(bool video, int fault)
	{
		target_video = video;
		rejected.clear();
		retries.store(0);
		mode.store(fault);
	}

	void DecoderFault::Disarm()
	{
		mode.store(0);
		rejected.clear();
	}

	int DecoderFault::SendError(bool video, const AVPacket * packet)
	{
		int fault = mode.load();
		if (fault == 0 || video != target_video) {
			return 0;
		}
		if (fault == 1 && packet != nullptr) {
			rejected.assign(packet->data, packet->data + packet->size);
			rejected_pts = packet->pts;
			mode.store(5);
			return AVERROR(EAGAIN);
		}
		if (fault == 5) {
			mode.store(0);
			if (packet == nullptr || packet->pts != rejected_pts
					|| packet->size != static_cast<int>(rejected.size())
					|| !std::equal(rejected.begin(), rejected.end(), packet->data)) {
				return AVERROR_INVALIDDATA;
			}
			retries.fetch_add(1);
		}
		if (packet == nullptr) {
			if (fault == 2) {
				mode.store(0);
				return AVERROR_INVALIDDATA;
			}
			if (fault == 3) {
				mode.store(6);
			}
			if (fault == 4) {
				mode.store(7);
				return AVERROR(EAGAIN);
			}
			if (fault == 7) {
				mode.store(0);
				retries.fetch_add(1);
			}
		}
		return 0;
	}

	int DecoderFault::ReceiveError(bool video)
	{
		if (mode.load() == 6 && video == target_video) {
			mode.store(0);
			return AVERROR_INVALIDDATA;
		}
		return 0;
	}

	int DecoderFault::Retries()
	{
		return retries.load();
	}
}
