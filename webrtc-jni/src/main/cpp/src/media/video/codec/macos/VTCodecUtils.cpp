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

#include "media/video/codec/macos/VTCodecUtils.h"

namespace jni
{
	namespace
	{
		CFStringRef GetPrimaries(uint8_t primaries)
		{
			switch (primaries) {
				case 1: return kCMFormatDescriptionColorPrimaries_ITU_R_709_2;
				case 5: return kCMFormatDescriptionColorPrimaries_EBU_3213;
				case 6:
				case 7: return kCMFormatDescriptionColorPrimaries_SMPTE_C;
				case 9: return kCMFormatDescriptionColorPrimaries_ITU_R_2020;
				default: return nullptr;
			}
		}

		CFStringRef GetTransfer(uint8_t transfer)
		{
			switch (transfer) {
				// BT.601 has the transfer function of BT.709.
				case 1:
				case 6: return kCMFormatDescriptionTransferFunction_ITU_R_709_2;
				case 7: return kCMFormatDescriptionTransferFunction_SMPTE_240M_1995;
				case 14:
				case 15: return kCMFormatDescriptionTransferFunction_ITU_R_2020;
				case 16: return kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ;
				case 18: return kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG;
				default: return nullptr;
			}
		}

		CFStringRef GetMatrix(uint8_t matrix)
		{
			switch (matrix) {
				case 1: return kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2;
				case 5:
				case 6: return kCMFormatDescriptionYCbCrMatrix_ITU_R_601_4;
				case 7: return kCMFormatDescriptionYCbCrMatrix_SMPTE_240M_1995;
				case 9: return kCMFormatDescriptionYCbCrMatrix_ITU_R_2020;
				default: return nullptr;
			}
		}
	}

	ScopedCF<CFMutableDictionaryRef> CreateCFDictionary()
	{
		return ScopedCF<CFMutableDictionaryRef>(CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
			&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks));
	}

	bool SetCFNumber(CFMutableDictionaryRef dictionary, CFStringRef key, int64_t value)
	{
		ScopedCF<CFNumberRef> number(CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt64Type, &value));

		if (!number) {
			return false;
		}

		CFDictionarySetValue(dictionary, key, number.get());

		return true;
	}

	void SetColorExtensions(CFMutableDictionaryRef extensions, uint8_t primaries, uint8_t transfer,
		uint8_t matrix)
	{
		if (CFStringRef value = GetPrimaries(primaries)) {
			CFDictionarySetValue(extensions, kCMFormatDescriptionExtension_ColorPrimaries, value);
		}
		if (CFStringRef value = GetTransfer(transfer)) {
			CFDictionarySetValue(extensions, kCMFormatDescriptionExtension_TransferFunction, value);
		}
		if (CFStringRef value = GetMatrix(matrix)) {
			CFDictionarySetValue(extensions, kCMFormatDescriptionExtension_YCbCrMatrix, value);
		}
	}

	bool IsUsingHardware(VTSessionRef session, CFStringRef property)
	{
		CFBooleanRef hardware = nullptr;
		bool usingHardware = false;

		if (VTSessionCopyProperty(session, property, kCFAllocatorDefault, &hardware) == noErr && hardware != nullptr) {
			usingHardware = CFBooleanGetValue(hardware);
			CFRelease(hardware);
		}

		return usingHardware;
	}
}
