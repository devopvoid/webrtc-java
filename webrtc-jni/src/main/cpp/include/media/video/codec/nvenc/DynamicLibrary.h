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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_DYNAMIC_LIBRARY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_DYNAMIC_LIBRARY_H_

#include <string>

namespace jni
{
	// A shared library loaded at run time, so that a library of a driver
	// that may not be installed never becomes a link dependency.
	class DynamicLibrary
	{
		public:
			DynamicLibrary() = default;
			~DynamicLibrary();

			DynamicLibrary(const DynamicLibrary &) = delete;
			DynamicLibrary & operator=(const DynamicLibrary &) = delete;

			// Loads the library by file name. On Windows only the system
			// directory is searched, where drivers install their libraries.
			bool Open(const std::string & name);

			bool IsOpen() const;

			// Returns the address of a symbol, or null.
			void * Symbol(const char * name) const;

			template <typename T>
			bool Resolve(const char * name, T & function) const
			{
				function = reinterpret_cast<T>(Symbol(name));

				return function != nullptr;
			}

		private:
			void * handle = nullptr;
	};
}

#endif
