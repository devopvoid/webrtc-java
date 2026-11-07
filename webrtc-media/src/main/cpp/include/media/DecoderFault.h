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

#ifndef WEBRTC_JAVA_MEDIA_DECODER_FAULT_H_
#define WEBRTC_JAVA_MEDIA_DECODER_FAULT_H_

extern "C" {
#include <libavcodec/packet.h>
}

namespace ffmpeg
{
	// Test-only JNI callers arm one fault for one stream at a time. Disarm
	// only after the player has closed. Normal playback pays an atomic load.
	class DecoderFault
	{
		public:
			// 1: reject a packet once with EAGAIN; 2: fail drain send;
			// 3: fail receiving buffered output after the drain request;
			// 4: reject the drain request once with EAGAIN.
			static void Arm(bool video, int mode);
			static void Disarm();
			static int SendError(bool video, const AVPacket * packet);
			static int ReceiveError(bool video);
			static int Retries();
	};
}

#endif
