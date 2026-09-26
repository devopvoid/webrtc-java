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

#ifndef JNI_WEBRTC_API_EXTENSION_API_H_
#define JNI_WEBRTC_API_EXTENSION_API_H_

#include "webrtc_java_api.h"

namespace jni
{
	// Returns the function table native extension libraries use to feed media
	// into the custom media sources of this library. See webrtc_java_api.h for
	// the interface itself and for how an extension gets hold of this address.
	//
	// The table is a singleton with static storage duration, so the returned
	// pointer stays valid for the lifetime of the process.
	const webrtc_java_api * GetExtensionApi();
}

#endif
