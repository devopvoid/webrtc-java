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

#include "media/video/codec/windows/MFEncoderUtils.h"
#include "platform/windows/WinUtils.h"

#include <mftransform.h>

using Microsoft::WRL::ComPtr;

namespace jni
{
	HRESULT EnumerateHardwareEncoders(const GUID & format, std::vector<ComPtr<IMFActivate>> & encoders)
	{
		MFT_REGISTER_TYPE_INFO input = { MFMediaType_Video, MFVideoFormat_NV12 };
		MFT_REGISTER_TYPE_INFO output = { MFMediaType_Video, format };

		IMFActivate ** activates = nullptr;
		UINT32 count = 0;

		// Sorted by merit, and restricted to what the system trusts.
		HRESULT hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER,
			&input, &output, &activates, &count);

		if (FAILED(hr)) {
			return hr;
		}

		for (UINT32 i = 0; i < count; i++) {
			ComPtr<IMFActivate> activate;
			activate.Attach(activates[i]);

			encoders.push_back(activate);
		}

		CoTaskMemFree(activates);

		return S_OK;
	}

	std::string GetTransformName(IMFActivate * activate)
	{
		LPWSTR name = nullptr;
		UINT32 length = 0;

		if (FAILED(activate->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, &length)) || name == nullptr) {
			return "unknown";
		}

		std::string result = WideStrToStr(name);

		CoTaskMemFree(name);

		return result;
	}
}
