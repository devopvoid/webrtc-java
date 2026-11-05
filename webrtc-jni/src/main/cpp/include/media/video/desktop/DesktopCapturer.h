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
#include <mutex>

namespace jni
{
	class DesktopCaptureCallback;

	class DesktopCapturer
	{
		public:
			explicit DesktopCapturer(bool screenCapturer);
			~DesktopCapturer();

			// Starts capturing and keeps the callback until this capturer is
			// destroyed. Returns false if it has been started before: WebRTC's
			// capturers take a single callback.
			bool Start(std::unique_ptr<DesktopCaptureCallback> callback);

			// Captures a frame. Without a capture backend, the callback gets
			// ERROR_PERMANENT. Returns false if there is no callback yet, which
			// a capturer of WebRTC would dereference.
			bool CaptureFrame();

			// webrtc::DesktopCapturer implementations.
			void SetMaxFrameRate(uint32_t max_frame_rate);
			void SetSharedMemoryFactory(std::unique_ptr<webrtc::SharedMemoryFactory> factory);
			void SetExcludedWindow(webrtc::WindowId window);
			bool GetSourceList(webrtc::DesktopCapturer::SourceList * sources);
			bool SelectSource(webrtc::DesktopCapturer::SourceId id);
			bool FocusOnSelectedSource();
			void setFocusSelectedSource(bool focus);
			bool IsOccluded(const webrtc::DesktopVector & pos);
			bool isWindowCapturer() const;

		protected:
			std::mutex startMutex;

			// Declared before the capturer, which uses it until destroyed.
			std::unique_ptr<DesktopCaptureCallback> captureCallback;

			std::unique_ptr<webrtc::DesktopCapturer> capturer;

			bool focusSelectedSource;

			const bool windowCapturer;

#if defined(WEBRTC_WIN)
		private:
			ComInitializer comInitializer;
#endif
	};
}

#endif
