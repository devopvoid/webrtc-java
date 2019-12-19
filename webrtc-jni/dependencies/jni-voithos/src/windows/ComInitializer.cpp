/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "windows/ComInitializer.h"
#include "windows/WinUtils.h"

namespace jni
{
	ComInitializer::ComInitializer() : initialized(false)
	{
		HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);

		if (hr != RPC_E_CHANGED_MODE) {
			THROW_IF_FAILED(hr, "Initialize COM failed.");

			initialized = true;
		}
	}

	ComInitializer::~ComInitializer()
	{
		if (initialized) {
			CoUninitialize();
		}
	}

	bool ComInitializer::isInitialized()
	{
		return initialized;
	}
}