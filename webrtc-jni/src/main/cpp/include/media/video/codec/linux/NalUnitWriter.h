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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NAL_UNIT_WRITER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NAL_UNIT_WRITER_H_

#include <cstddef>
#include <cstdint>
#include <vector>

namespace jni
{
	// Writes one H.264 or H.265 NAL unit, header included, bit by bit, as the
	// VA-API encoders hand the parameter sets and slice headers to drivers
	// that do not write them themselves.
	class NalUnitWriter
	{
		public:
			NalUnitWriter();

			// The low count bits of value, the most significant first.
			void Bits(uint32_t value, int count);
			void Flag(bool value);
			// Exponential-Golomb codes, ue(v) and se(v).
			void Ue(uint32_t value);
			void Se(int32_t value);
			// A one bit and zero bits up to the next byte: rbsp_trailing_bits()
			// at the end of a parameter set, byte_alignment() at the end of an
			// H.265 slice segment header.
			void AlignWithStopBit();

			// Appends the NAL unit with a four byte start code and emulation
			// prevention bytes, and returns how many bits that took. A last
			// partial byte, the end of an H.264 slice header the driver goes on
			// writing, is appended padded with zeros and counted without them.
			size_t AppendTo(std::vector<uint8_t> & output) const;

		private:
			std::vector<uint8_t> bytes;
			// The bits used in the last byte, 0 when it is full.
			int bitsInLastByte;
	};
}

#endif
