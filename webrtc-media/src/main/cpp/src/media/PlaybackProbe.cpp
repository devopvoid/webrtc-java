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

#include "media/PlaybackProbe.h"

#include <atomic>

namespace
{
	std::atomic<int64_t> seeks{ 0 };
	std::atomic<int> waiting_pushes{ 0 };
	std::atomic<int> waiting_players{ 0 };
}

namespace ffmpeg
{
	void PlaybackProbe::BeginCommandWait()
	{
		waiting_players.fetch_add(1);
	}

	void PlaybackProbe::EndCommandWait()
	{
		waiting_players.fetch_sub(1);
	}

	int PlaybackProbe::WaitingPlayers()
	{
		return waiting_players.load();
	}

	void PlaybackProbe::NoteSeek()
	{
		seeks.fetch_add(1);
	}

	int64_t PlaybackProbe::Seeks()
	{
		return seeks.load();
	}

	PlaybackProbe::WaitingPush::WaitingPush()
	{
		waiting_pushes.fetch_add(1);
	}

	PlaybackProbe::WaitingPush::~WaitingPush()
	{
		waiting_pushes.fetch_sub(1);
	}

	int PlaybackProbe::WaitingPushes()
	{
		return waiting_pushes.load();
	}
}
