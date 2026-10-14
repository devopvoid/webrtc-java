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

#include "media/video/codec/linux/NalUnitWriter.h"

namespace jni
{
	NalUnitWriter::NalUnitWriter() :
		bitsInLastByte(0)
	{
	}

	void NalUnitWriter::Bits(uint32_t value, int count)
	{
		for (int bit = count - 1; bit >= 0; bit--) {
			if (bitsInLastByte == 0) {
				bytes.push_back(0);
			}

			bytes.back() |= static_cast<uint8_t>(((value >> bit) & 1) << (7 - bitsInLastByte));
			bitsInLastByte = (bitsInLastByte + 1) % 8;
		}
	}

	void NalUnitWriter::Flag(bool value)
	{
		Bits(value ? 1 : 0, 1);
	}

	void NalUnitWriter::Ue(uint32_t value)
	{
		// value + 1 in binary, after as many zeros as it has bits less one.
		const uint64_t coded = static_cast<uint64_t>(value) + 1;
		int length = 0;

		while ((coded >> length) > 1) {
			length++;
		}

		Bits(0, length);

		for (int bit = length; bit >= 0; bit--) {
			Bits(static_cast<uint32_t>((coded >> bit) & 1), 1);
		}
	}

	void NalUnitWriter::Se(int32_t value)
	{
		// Positive values to odd codes, the others to even ones.
		const int64_t wide = value;
		Ue(static_cast<uint32_t>(wide > 0 ? 2 * wide - 1 : -2 * wide));
	}

	void NalUnitWriter::AlignWithStopBit()
	{
		Bits(1, 1);

		if (bitsInLastByte != 0) {
			Bits(0, 8 - bitsInLastByte);
		}
	}

	size_t NalUnitWriter::AppendTo(std::vector<uint8_t> & output) const
	{
		const size_t start = output.size();

		output.insert(output.end(), { 0, 0, 0, 1 });

		int zeros = 0;

		for (uint8_t byte : bytes) {
			// No three bytes 00 00 0x with x up to 3 inside the unit.
			if (zeros >= 2 && byte <= 3) {
				output.push_back(3);
				zeros = 0;
			}

			output.push_back(byte);
			zeros = byte == 0 ? zeros + 1 : 0;
		}

		const size_t padding = bitsInLastByte == 0 ? 0 : 8 - bitsInLastByte;

		return (output.size() - start) * 8 - padding;
	}
}
