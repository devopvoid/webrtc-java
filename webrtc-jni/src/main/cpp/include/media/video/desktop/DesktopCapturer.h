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

#ifndef JNI_WEBRTC_MEDIA_DESKTOP_CAPTURER_H_
#define JNI_WEBRTC_MEDIA_DESKTOP_CAPTURER_H_

#if defined(WEBRTC_WIN)
#include "platform/windows/ComInitializer.h"
#endif

#include "modules/desktop_capture/desktop_capturer.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class DesktopCapturer : public webrtc::DesktopCapturer
	{
		public:
			explicit DesktopCapturer(bool screenCapturer);
			~DesktopCapturer() override;

			// webrtc::DesktopCapturer implementations.
			void Start(Callback * callback) override;
			void SetSharedMemoryFactory(std::unique_ptr<webrtc::SharedMemoryFactory> factory) override;
			void CaptureFrame() override;
			void SetExcludedWindow(webrtc::WindowId window) override;
			bool GetSourceList(SourceList * sources) override;
			bool SelectSource(SourceId id) override;
			bool FocusOnSelectedSource() override;
			void setFocusSelectedSource(bool focus);
			bool IsOccluded(const webrtc::DesktopVector & pos) override;

		protected:
			std::unique_ptr<webrtc::DesktopCapturer> capturer;

			bool focusSelectedSource;

#if defined(WEBRTC_WIN)
		private:
			ComInitializer comInitializer;
#endif
	};
}

#endif
