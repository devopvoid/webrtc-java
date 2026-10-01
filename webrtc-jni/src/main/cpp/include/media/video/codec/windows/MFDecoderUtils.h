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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_DECODER_UTILS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_DECODER_UTILS_H_

#include <d3d11.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <wrl/client.h>

#include <string>
#include <vector>

namespace jni
{
	// Creates a Direct3D 11 device on the default adapter that video can be
	// decoded on, protected for use from several threads, as a decoder
	// transform and the thread reading its output both use it.
	HRESULT CreateVideoDevice(Microsoft::WRL::ComPtr<ID3D11Device> & device,
		Microsoft::WRL::ComPtr<ID3D11DeviceContext> & context);

	// Whether the GPU of the device decodes the given DXVA profile, such as
	// D3D11_DECODER_PROFILE_H264_VLD_NOFGT, in hardware.
	bool SupportsDecoderProfile(ID3D11Device * device, const GUID & profile);

	// Lists the decoder transforms for the given video format, such as
	// MFVideoFormat_H264, best first. These are the synchronous transforms of
	// Windows, which decode on the GPU through DXVA when given a Direct3D
	// device. Media Foundation has to be started.
	HRESULT EnumerateDecoders(const GUID & format, std::vector<Microsoft::WRL::ComPtr<IMFActivate>> & decoders);

	// Activates the first of the decoders that can decode on a Direct3D 11
	// device, and gives it the device manager. The activation object of the
	// transform is returned with it, to shut it down with.
	HRESULT ActivateDirect3DDecoder(const std::vector<Microsoft::WRL::ComPtr<IMFActivate>> & decoders,
		IMFDXGIDeviceManager * manager, Microsoft::WRL::ComPtr<IMFActivate> & activate,
		Microsoft::WRL::ComPtr<IMFTransform> & transform);
}

#endif
