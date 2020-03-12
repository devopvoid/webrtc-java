/*
 * Copyright 2019 Alex Andres
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

#include "platform/windows/MFInitializer.h"
#include "platform/windows/WinUtils.h"

#include <mfapi.h>

namespace jni
{
	MFInitializer::MFInitializer() : initialized(false)
	{
		HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_LITE);
		THROW_IF_FAILED(hr, "Initialize Media Foundation failed.");

		initialized = true;
	}

	MFInitializer::~MFInitializer()
	{
		if (initialized) {
			MFShutdown();
		}
	}

	bool MFInitializer::isInitialized()
	{
		return initialized;
	}
}