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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_CODEC_UTILS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_CODEC_UTILS_H_

#include <CoreFoundation/CoreFoundation.h>
#include <CoreMedia/CoreMedia.h>
#include <VideoToolbox/VideoToolbox.h>

#include <cstdint>

namespace jni
{
	// Releases a Core Foundation object when it goes out of scope.
	template <typename T>
	class ScopedCF
	{
		public:
			ScopedCF() : ref(nullptr) {}
			explicit ScopedCF(T ref) : ref(ref) {}
			~ScopedCF() { if (ref) CFRelease(ref); }

			ScopedCF(const ScopedCF &) = delete;
			ScopedCF & operator=(const ScopedCF &) = delete;

			T get() const { return ref; }
			T * receive() { return &ref; }
			explicit operator bool() const { return ref != nullptr; }

			// Gives up ownership.
			T release() { T result = ref; ref = nullptr; return result; }

		private:
			T ref;
	};

	ScopedCF<CFMutableDictionaryRef> CreateCFDictionary();

	// Sets an integer value in a dictionary. Returns false if the number
	// cannot be created.
	bool SetCFNumber(CFMutableDictionaryRef dictionary, CFStringRef key, int64_t value);

	// Writes the colour of a stream, given as the code points of ISO/IEC
	// 23091-2 (H.273) that H.265, VP9 and AV1 share, into the extensions of a
	// format description. Code points without a Core Media counterpart, and
	// unspecified ones, are left out.
	void SetColorExtensions(CFMutableDictionaryRef extensions, uint8_t primaries, uint8_t transfer,
		uint8_t matrix);

	// Whether a VideoToolbox session works in hardware, as the session reports
	// it under the given property. Requiring hardware when the session is
	// created is not relied on alone.
	bool IsUsingHardware(VTSessionRef session, CFStringRef property);
}

#endif
