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

#ifndef JNI_AUDIO_BACKEND_UNAVAILABLE_EXCEPTION_H_
#define JNI_AUDIO_BACKEND_UNAVAILABLE_EXCEPTION_H_

#include <stdexcept>

namespace jni {
	// Expected backend absence, distinct from other device initialization failures.
	class AudioBackendUnavailableException : public std::runtime_error {
	public:
		using std::runtime_error::runtime_error;
	};
}

#endif
