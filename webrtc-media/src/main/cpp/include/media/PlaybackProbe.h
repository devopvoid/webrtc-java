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

#ifndef WEBRTC_JAVA_MEDIA_PLAYBACK_PROBE_H_
#define WEBRTC_JAVA_MEDIA_PLAYBACK_PROBE_H_

#include <cstdint>

namespace ffmpeg
{
	// Shows the tests what a player's decode thread is up to, which nothing
	// in the player's interface does: a seek takes effect on the position at
	// once, and a thread held up by a full queue looks like one that is done.
	//
	// Only the tests read it, through JNI_PlaybackProbe, and it costs a player
	// a single atomic update per seek and per wait for room in a queue. The
	// counts are those of all players, which tests one at a time can tell
	// apart.
	class PlaybackProbe
	{
		public:
			// Notes a seek that the decode thread has carried out.
			static void NoteSeek();

			// How many seeks have been carried out so far.
			static int64_t Seeks();

			// Counts a push as waiting for room in a queue for as long as
			// this lives.
			class WaitingPush
			{
				public:
					WaitingPush();
					~WaitingPush();

					WaitingPush(const WaitingPush &) = delete;
					WaitingPush & operator=(const WaitingPush &) = delete;
			};

			// How many pushes are waiting for room in a queue right now.
			static int WaitingPushes();
	};
}

#endif
