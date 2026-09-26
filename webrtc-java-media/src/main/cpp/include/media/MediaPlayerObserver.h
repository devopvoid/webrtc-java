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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_PLAYER_OBSERVER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_PLAYER_OBSERVER_H_

#include <string>

namespace ffmpeg
{
	// What a player reports while it runs.
	//
	// Every call arrives on the player's own thread, never on the thread that
	// asked for playback, so an implementation must not block: the thread it
	// holds up is the one decoding the media.
	class MediaPlayerObserver
	{
		public:
			virtual ~MediaPlayerObserver() = default;

			// The player moved to another state, as a MediaPlayerState value.
			virtual void OnStateChanged(int state) = 0;

			// The source ran out. A looping player never reports this, since
			// it starts again instead.
			virtual void OnEndOfStream() = 0;

			// Playback stopped because something went wrong, with the message
			// FFmpeg gave for it.
			virtual void OnError(const std::string & message) = 0;
	};
}

#endif
