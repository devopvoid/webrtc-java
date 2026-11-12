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

#ifndef JNI_WEBRTC_MEDIA_DESKTOP_MACOS_SCREEN_CAPTURER_H_
#define JNI_WEBRTC_MEDIA_DESKTOP_MACOS_SCREEN_CAPTURER_H_

#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/desktop_capture/desktop_capturer.h"

#include <memory>

namespace jni
{
	// Creates a capturer for one screen, or for the whole desktop, that picks
	// its backend when started. A single screen is captured with
	// ScreenCaptureKit on macOS 14 and newer, which draws the cursor itself.
	// The whole desktop, which ScreenCaptureKit cannot capture, and every
	// screen on older macOS, are captured with WebRTC's CGDisplayStream
	// capturer. That capturer also lists the screens, since ScreenCaptureKit
	// lists none without its system picker; both name a screen by its
	// CGDirectDisplayID.
	std::unique_ptr<webrtc::DesktopCapturer> CreateMacOSScreenCapturer(const webrtc::DesktopCaptureOptions & options);
}

#endif
