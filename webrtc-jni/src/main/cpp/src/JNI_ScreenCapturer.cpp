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

#include "JNI_ScreenCapturer.h"
#include "JavaUtils.h"
#include "media/video/desktop/DesktopCapturer.h"

#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/desktop_capture/desktop_and_cursor_composer.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_ScreenCapturer_initialize
(JNIEnv * env, jobject caller)
{
	auto options = webrtc::DesktopCaptureOptions::CreateDefault();
	// Enable desktop effects.
	options.set_disable_effects(false);

#if defined(WEBRTC_WIN)
	options.set_allow_directx_capturer(true);
#endif

    auto capturer = new webrtc::DesktopAndCursorComposer(
        webrtc::DesktopCapturer::CreateScreenCapturer(options),
        options);

	SetHandle(env, caller, new jni::DesktopCapturer(capturer));
}