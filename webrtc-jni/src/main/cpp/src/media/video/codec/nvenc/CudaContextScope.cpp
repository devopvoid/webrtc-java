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

#include "media/video/codec/nvenc/CudaContextScope.h"

namespace jni
{
	CudaContextScope::CudaContextScope(const NvencLibrary & library, CUcontext context) :
		library(library),
		pushed(context != nullptr && library.PushContext(context))
	{
	}

	CudaContextScope::~CudaContextScope()
	{
		if (pushed) {
			library.PopContext();
		}
	}

	bool CudaContextScope::IsCurrent() const
	{
		return pushed;
	}
}
