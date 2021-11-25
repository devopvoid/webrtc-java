/*
 * Copyright 2021 Alex Andres
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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_CONVERTER_H_
#define JNI_WEBRTC_MEDIA_AUDIO_CONVERTER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

#include "rtc_base/constructor_magic.h"

namespace jni
{
	class AudioConverter
	{
		public:
			static std::unique_ptr<AudioConverter> create(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels);
			
			virtual ~AudioConverter() = default;

			virtual void convert(const int16_t * src, size_t srcSize, int16_t * dst, size_t dstSize) = 0;

			size_t getSrcChannels() const { return srcChannels; }
			size_t getSrcFrames() const { return srcFrames; }
			size_t getDstChannels() const { return dstChannels; }
			size_t getDstFrames() const { return dstFrames; }

		protected:
			AudioConverter();
			AudioConverter(size_t srcFrames, size_t srcChannels, size_t dstFrames, size_t dstChannels);

			void checkSizes(size_t srcSize, size_t dstCapacity) const;

			const size_t srcFrames;
			const size_t srcChannels;
			const size_t dstFrames;
			const size_t dstChannels;

		private:
			RTC_DISALLOW_COPY_AND_ASSIGN(AudioConverter);
	};
}

#endif