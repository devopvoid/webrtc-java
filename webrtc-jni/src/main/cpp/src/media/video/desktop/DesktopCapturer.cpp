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

#include "media/video/desktop/DesktopCapturer.h"
#include "media/video/desktop/DesktopCaptureCallback.h"

#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/desktop_capture/desktop_and_cursor_composer.h"
#include "rtc_base/logging.h"

namespace jni
{
	DesktopCapturer::DesktopCapturer(bool screenCapturer) :
		focusSelectedSource(false),
		windowCapturer(!screenCapturer)
	{
		auto options = webrtc::DesktopCaptureOptions::CreateDefault();
		// Enable desktop effects.
		options.set_disable_effects(false);

#if defined(WEBRTC_MAC)
		options.set_allow_iosurface(true);
#endif
#if defined(WEBRTC_WIN)
		options.set_allow_directx_capturer(true);
#endif
#if defined(WEBRTC_USE_PIPEWIRE)
		// Wayland: route capture through the PipeWire/xdg-desktop-portal backend.
		// Only takes effect when IsRunningUnderWayland(); X11 sessions keep the
		// X11 capturer. The portal delivers frames with the cursor already
		// composited, so prefer it over DesktopAndCursorComposer's software cursor.
		options.set_allow_pipewire(true);
		options.set_prefer_cursor_embedded(true);
#endif

		std::unique_ptr<webrtc::DesktopCapturer> inner = screenCapturer
			? webrtc::DesktopCapturer::CreateScreenCapturer(options)
			: webrtc::DesktopCapturer::CreateWindowCapturer(options);

		if (!inner) {
			RTC_LOG(LS_ERROR) << (screenCapturer
				? "DesktopCapturer: CreateScreenCapturer returned null. "
				  "Possible causes: missing X11/XDAMAGE extension, Wayland "
				  "session without PipeWire support, or snap confinement."
				: "DesktopCapturer: CreateWindowCapturer returned null. "
				  "Possible causes: missing X11/XFIXES extension or snap confinement.");
			// Leave capturer as nullptr: GetSourceList returns false, and
			// CaptureFrame reports ERROR_PERMANENT.
			return;
		}

		capturer = std::make_unique<webrtc::DesktopAndCursorComposer>(std::move(inner), options);
	}

	DesktopCapturer::~DesktopCapturer()
	{
		capturer.reset();
	}

	bool DesktopCapturer::Start(std::unique_ptr<DesktopCaptureCallback> callback)
	{
		std::lock_guard<std::mutex> lock(startMutex);

		if (captureCallback) {
			return false;
		}

		if (capturer) {
			capturer->Start(callback.get());

			if (focusSelectedSource) {
				capturer->FocusOnSelectedSource();
			}
		}

		captureCallback = std::move(callback);

		return true;
	}

	void DesktopCapturer::SetMaxFrameRate(uint32_t max_frame_rate)
	{
		if (!capturer) return;
		capturer->SetMaxFrameRate(max_frame_rate);
	}

	void DesktopCapturer::SetSharedMemoryFactory(std::unique_ptr<webrtc::SharedMemoryFactory> factory)
	{
		if (!capturer) return;
		capturer->SetSharedMemoryFactory(std::move(factory));
	}

	bool DesktopCapturer::CaptureFrame()
	{
		DesktopCaptureCallback * callback = nullptr;

		{
			// Not held while capturing: the result may call back into this
			// capturer on the same thread.
			std::lock_guard<std::mutex> lock(startMutex);

			callback = captureCallback.get();
		}

		if (callback == nullptr) {
			return false;
		}

		if (!capturer) {
			callback->OnCaptureResult(webrtc::DesktopCapturer::Result::ERROR_PERMANENT, nullptr);
			return true;
		}

#if defined(WEBRTC_MAC)
		CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0, true);
#endif

		capturer->CaptureFrame();

		return true;
	}

	void DesktopCapturer::SetExcludedWindow(webrtc::WindowId window)
	{
		if (!capturer) return;
		capturer->SetExcludedWindow(window);
	}

	bool DesktopCapturer::GetSourceList(webrtc::DesktopCapturer::SourceList * sources)
	{
		if (!capturer) return false;
		return capturer->GetSourceList(sources);
	}

	bool DesktopCapturer::SelectSource(webrtc::DesktopCapturer::SourceId id)
	{
		if (!capturer) return false;
		return capturer->SelectSource(id);
	}

	bool DesktopCapturer::FocusOnSelectedSource()
	{
		if (!capturer) return false;
		return capturer->FocusOnSelectedSource();
	}

	void DesktopCapturer::setFocusSelectedSource(bool focus)
	{
		this->focusSelectedSource = focus;
	}

	bool DesktopCapturer::IsOccluded(const webrtc::DesktopVector & pos)
	{
		if (!capturer) return false;
		return capturer->IsOccluded(pos);
	}

	bool DesktopCapturer::isWindowCapturer() const
	{
		return windowCapturer;
	}
}
