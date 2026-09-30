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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_CUDA_CONTEXT_SCOPE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_CUDA_CONTEXT_SCOPE_H_

#include "media/video/codec/nvenc/NvencLibrary.h"

namespace jni
{
	// Makes a CUDA context current on the calling thread for as long as it
	// lives, which NVENC calls on a CUDA device need.
	class CudaContextScope
	{
		public:
			CudaContextScope(const NvencLibrary & library, CUcontext context);
			~CudaContextScope();

			CudaContextScope(const CudaContextScope &) = delete;
			CudaContextScope & operator=(const CudaContextScope &) = delete;

			bool IsCurrent() const;

		private:
			const NvencLibrary & library;
			const bool pushed;
	};
}

#endif
