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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_DESKTOP_CAPTURER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_DESKTOP_CAPTURER_H_

#if defined(WEBRTC_WIN)
#include "platform/windows/ComInitializer.h"
#endif

#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_and_cursor_composer.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class DesktopCapturer
	{
		public:
			DesktopCapturer(webrtc::DesktopCapturer * capturer);
			~DesktopCapturer();

			DesktopCapturer(const DesktopCapturer &) = delete;
            DesktopCapturer & operator=(const DesktopCapturer &) = delete;

			// webrtc::DesktopCapturer implementations.
			void Start(webrtc::DesktopCapturer::Callback * callback);
			void SetSharedMemoryFactory(std::unique_ptr<webrtc::SharedMemoryFactory> factory);
			void SetMaxFrameRate(uint32_t max_frame_rate);
			void CaptureFrame();
			void SetExcludedWindow(webrtc::WindowId window);
			bool GetSourceList(webrtc::DesktopCapturer::SourceList * sources);
			bool SelectSource(webrtc::DesktopCapturer::SourceId id);
			bool FocusOnSelectedSource();
			void setFocusSelectedSource(bool focus);
			bool IsOccluded(const webrtc::DesktopVector & pos);

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
