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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_RECORDER_OBSERVER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_RECORDER_OBSERVER_H_

#include <string>

namespace ffmpeg
{
	// What a MediaRecorder reports while it runs. Every call arrives on the
	// recorder's writer thread, which must not be held up for long.
	class MediaRecorderObserver
	{
		public:
			virtual ~MediaRecorderObserver() = default;

			// The file header was written, and media now goes into the file.
			virtual void OnStarted() = 0;

			// Something went wrong that the recording carries on without,
			// such as a track that cannot be recorded.
			virtual void OnWarning(const std::string & message) = 0;

			// Writing failed; nothing more goes into the file.
			virtual void OnError(const std::string & message) = 0;

			// The given video track waits for a key frame, and the sender or
			// receiver it records should be asked for one.
			virtual void OnKeyFrameNeeded(int track) = 0;
	};
}

#endif
