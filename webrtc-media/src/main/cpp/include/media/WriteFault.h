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

#ifndef WEBRTC_JAVA_MEDIA_WRITE_FAULT_H_
#define WEBRTC_JAVA_MEDIA_WRITE_FAULT_H_

namespace ffmpeg
{
	// Makes the writing of a recording fail on purpose, so that the tests can
	// see what a recorder does when a disk fills up or a file cannot be
	// closed, which no disk does on demand.
	//
	// Only the tests arm it, through JNI_WriteFault, and a call that is not
	// armed pays a single atomic load. The call itself still runs, so the
	// file is as it would be; only the result it reports is replaced. A
	// fault fires once, for the recorder that runs into it first.
	class WriteFault
	{
		public:
			enum class Call
			{
				kHeader,
				kPacket,
				kTrailer,
				kClose
			};

			// Fails the call that follows the given number of calls of the
			// same kind, once, with the given AVERROR.
			static void Fail(Call call, int error, int after_calls);

			// Forgets a fault that has not fired.
			static void Disarm();

			// Returns the result of the given call, or the error it is to
			// fail with if a fault is armed for it, which disarms the fault.
			static int Check(Call call, int result);
	};
}

#endif
