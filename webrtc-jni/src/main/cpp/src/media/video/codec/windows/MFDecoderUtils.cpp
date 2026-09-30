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

#include "media/video/codec/windows/MFDecoderUtils.h"

#include <d3d10.h>
#include <mferror.h>

using Microsoft::WRL::ComPtr;

namespace jni
{
	HRESULT CreateVideoDevice(ComPtr<ID3D11Device> & device, ComPtr<ID3D11DeviceContext> & context)
	{
		const D3D_FEATURE_LEVEL levels[] = {
			D3D_FEATURE_LEVEL_11_1,
			D3D_FEATURE_LEVEL_11_0,
			D3D_FEATURE_LEVEL_10_1,
			D3D_FEATURE_LEVEL_10_0
		};

		HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
			D3D11_CREATE_DEVICE_VIDEO_SUPPORT, levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
			&device, nullptr, &context);

		if (FAILED(hr)) {
			return hr;
		}

		ComPtr<ID3D10Multithread> multithread;
		hr = device.As(&multithread);

		if (SUCCEEDED(hr)) {
			multithread->SetMultithreadProtected(TRUE);
		}

		return hr;
	}

	bool SupportsDecoderProfile(ID3D11Device * device, const GUID & profile)
	{
		ComPtr<ID3D11VideoDevice> videoDevice;

		if (FAILED(device->QueryInterface(IID_PPV_ARGS(&videoDevice)))) {
			return false;
		}

		const UINT count = videoDevice->GetVideoDecoderProfileCount();

		for (UINT i = 0; i < count; i++) {
			GUID supported;

			if (SUCCEEDED(videoDevice->GetVideoDecoderProfile(i, &supported)) && IsEqualGUID(supported, profile)) {
				BOOL nv12 = FALSE;

				// Decoded into NV12, the format that is read back.
				return SUCCEEDED(videoDevice->CheckVideoDecoderFormat(&profile, DXGI_FORMAT_NV12, &nv12)) && nv12;
			}
		}

		return false;
	}

	HRESULT EnumerateDecoders(const GUID & format, std::vector<ComPtr<IMFActivate>> & decoders)
	{
		MFT_REGISTER_TYPE_INFO input = { MFMediaType_Video, format };

		IMFActivate ** activates = nullptr;
		UINT32 count = 0;

		HRESULT hr = MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER,
			MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
			&input, nullptr, &activates, &count);

		if (FAILED(hr)) {
			return hr;
		}

		for (UINT32 i = 0; i < count; i++) {
			ComPtr<IMFActivate> activate;
			activate.Attach(activates[i]);

			decoders.push_back(activate);
		}

		CoTaskMemFree(activates);

		return S_OK;
	}

	HRESULT ActivateDirect3DDecoder(const std::vector<ComPtr<IMFActivate>> & decoders, IMFDXGIDeviceManager * manager,
		ComPtr<IMFActivate> & activate, ComPtr<IMFTransform> & transform)
	{
		for (const ComPtr<IMFActivate> & candidate : decoders) {
			ComPtr<IMFTransform> candidateTransform;

			if (FAILED(candidate->ActivateObject(IID_PPV_ARGS(&candidateTransform)))) {
				continue;
			}

			ComPtr<IMFAttributes> attributes;
			UINT32 aware = FALSE;

			if (SUCCEEDED(candidateTransform->GetAttributes(&attributes)) &&
				SUCCEEDED(attributes->GetUINT32(MF_SA_D3D11_AWARE, &aware)) && aware &&
				SUCCEEDED(candidateTransform->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER,
					reinterpret_cast<ULONG_PTR>(manager))))
			{
				activate = candidate;
				transform = candidateTransform;

				return S_OK;
			}

			candidateTransform.Reset();
			candidate->ShutdownObject();
		}

		return MF_E_TOPO_CODEC_NOT_FOUND;
	}
}
