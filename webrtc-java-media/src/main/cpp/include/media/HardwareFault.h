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

#ifndef WEBRTC_JAVA_MEDIA_HARDWARE_FAULT_H_
#define WEBRTC_JAVA_MEDIA_HARDWARE_FAULT_H_

namespace ffmpeg
{
	// Makes the hardware video decoder fail on purpose, so that the fall back
	// to software can be tested: a GPU cannot be made to fail on demand.
	//
	// Only the tests arm it, through JNI_HardwareFault, and a decoder that is
	// not armed pays a single atomic load per packet. A fault applies to the
	// hardware decoder only, never to the software one that takes over, and
	// is armed for the next decoder that runs into it.
	class HardwareFault
	{
		public:
			// Fails the hardware decoder's send of the packet that follows the
			// given number of them, once, with the given AVERROR.
			static void FailSend(int error, int after_packets);

			// Makes the hardware decoder produce nothing, and fail with the
			// given AVERROR once it is asked for pictures after it was told
			// to drain: the failure of a decoder that never delivered.
			static void FailDrain(int error);

			// Forgets any fault, and the software decoder noted last.
			static void Disarm();

			// Notes how many threads a software decoder of the video decoder
			// got, for a test to see that one that took over from the hardware
			// has all it can use.
			static void NoteSoftwareDecoder(int threads);

			// The thread count noted last, or 0.
			static int SoftwareThreads();

			// The error to fail the next send with, or 0.
			static int NextSendError();

			// Whether the hardware decoder's pictures are held back.
			static bool HoldsPictures();

			// The error to fail the receive of a picture with once draining.
			static int DrainError();
	};
}

#endif
