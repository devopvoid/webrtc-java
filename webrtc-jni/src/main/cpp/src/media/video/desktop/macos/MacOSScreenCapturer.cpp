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

#include "media/video/desktop/macos/MacOSScreenCapturer.h"

#include "modules/desktop_capture/mac/screen_capturer_sck.h"
#include "rtc_base/logging.h"

namespace jni
{
	namespace
	{
		class MacOSScreenCapturer final : public webrtc::DesktopCapturer
		{
			public:
				MacOSScreenCapturer(const webrtc::DesktopCaptureOptions & options,
					std::unique_ptr<webrtc::DesktopCapturer> displayStream) :
					options(options),
					displayStream(std::move(displayStream)),
					active(nullptr),
					screen(webrtc::kFullDesktopScreenId),
					maxFrameRate(0)
				{
				}

				void Start(Callback * callback) override
				{
					if (screen != webrtc::kFullDesktopScreenId && webrtc::ScreenCapturerSckAvailable()) {
						webrtc::DesktopCaptureOptions sckOptions = options;
						sckOptions.set_allow_sck_capturer(true);
						// ScreenCaptureKit's frames do not say where the screen
						// lies on the desktop, which DesktopAndCursorComposer
						// needs to place its cursor on any but the first screen.
						sckOptions.set_prefer_cursor_embedded(true);

						screenCaptureKit = webrtc::DesktopCapturer::CreateScreenCapturer(sckOptions);
					}

					if (screenCaptureKit && screenCaptureKit->SelectSource(screen)) {
						active = screenCaptureKit.get();
					}
					else {
						active = displayStream.get();
					}

					if (maxFrameRate > 0) {
						active->SetMaxFrameRate(maxFrameRate);
					}

					active->Start(callback);
				}

				void SetMaxFrameRate(uint32_t maxFrameRate) override
				{
					this->maxFrameRate = maxFrameRate;

					if (active) {
						active->SetMaxFrameRate(maxFrameRate);
					}
				}

				void CaptureFrame() override
				{
					if (active) {
						active->CaptureFrame();
					}
				}

				bool GetSourceList(SourceList * sources) override
				{
					return displayStream->GetSourceList(sources);
				}

				bool SelectSource(SourceId id) override
				{
					if (active) {
						// Selected after Start(): stays with the backend chosen
						// then.
						return active->SelectSource(id);
					}

					if (!displayStream->SelectSource(id)) {
						return false;
					}

					screen = id;

					return true;
				}

				bool FocusOnSelectedSource() override
				{
					return active ? active->FocusOnSelectedSource() : false;
				}

				bool IsOccluded(const webrtc::DesktopVector & pos) override
				{
					return active ? active->IsOccluded(pos) : false;
				}

			private:
				const webrtc::DesktopCaptureOptions options;

				// Lists the screens, and captures where ScreenCaptureKit does not.
				const std::unique_ptr<webrtc::DesktopCapturer> displayStream;
				std::unique_ptr<webrtc::DesktopCapturer> screenCaptureKit;
				webrtc::DesktopCapturer * active;

				SourceId screen;
				uint32_t maxFrameRate;
		};
	}

	std::unique_ptr<webrtc::DesktopCapturer> CreateMacOSScreenCapturer(const webrtc::DesktopCaptureOptions & options)
	{
		webrtc::DesktopCaptureOptions displayStreamOptions = options;
		displayStreamOptions.set_allow_sck_capturer(false);

		std::unique_ptr<webrtc::DesktopCapturer> displayStream =
			webrtc::DesktopCapturer::CreateScreenCapturer(displayStreamOptions);

		if (!displayStream) {
			return nullptr;
		}

		return std::make_unique<MacOSScreenCapturer>(options, std::move(displayStream));
	}
}
