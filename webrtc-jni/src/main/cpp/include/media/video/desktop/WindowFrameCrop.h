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

#ifndef JNI_WEBRTC_MEDIA_DESKTOP_WINDOW_FRAME_CROP_H_
#define JNI_WEBRTC_MEDIA_DESKTOP_WINDOW_FRAME_CROP_H_

#include "modules/desktop_capture/desktop_frame.h"
#include "modules/desktop_capture/desktop_geometry.h"

#include <algorithm>

namespace jni
{
	// Crops the black border of a frame captured from a window on Windows, for
	// window sources only. The window capturer's bitmaps have no row padding, so
	// a stride wider than a row means it cut the frame out of a larger bitmap to
	// drop a resize border. Windows without such a border come whole and keep
	// all their pixels. The crop values stay as they are when there is nothing
	// to crop, or when a frame too small for the border would be left empty.
	inline void CropWindowBorder(const webrtc::DesktopFrame & frame, int border, int * crop_x, int * crop_y, int * crop_w, int * crop_h)
	{
		const int width = frame.size().width();
		const int height = frame.size().height();

		if (frame.stride() == width * webrtc::DesktopFrame::kBytesPerPixel) {
			return;
		}

		const int top = std::max(0, -frame.top_left().y());
		const auto rect = webrtc::DesktopRect::MakeLTRB(border, top, width - border, height - border);

		if (rect.is_empty()) {
			return;
		}

		*crop_x = rect.left();
		*crop_y = rect.top();
		*crop_w = rect.width();
		*crop_h = rect.height();
	}
}

#endif