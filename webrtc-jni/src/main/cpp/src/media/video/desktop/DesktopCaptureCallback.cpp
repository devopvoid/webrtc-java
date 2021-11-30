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

#include "media/video/desktop/DesktopCaptureCallback.h"
#include "media/video/desktop/DesktopFrame.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JNI_WebRTC.h"

#include "modules/desktop_capture/desktop_frame.h"

#if defined(WEBRTC_WIN)
#include "rtc_base/win32.h"
#endif

namespace jni
{
	DesktopCaptureCallback::DesktopCaptureCallback(JNIEnv * env, const JavaGlobalRef<jobject> & callback) :
		callback(callback),
		javaClass(JavaClasses::get<JavaDesktopCaptureCallbackClass>(env))
	{
	}

	void DesktopCaptureCallback::OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jresult = JavaEnums::toJava(env, result);

		std::unique_ptr<webrtc::DesktopFrame> cFrame = std::move(frame);

#if defined(WEBRTC_WIN)
		// Crop black window borders.
		bool fullscreen = cFrame->stride() == (cFrame->size().width() * webrtc::DesktopFrame::kBytesPerPixel);

		if (!fullscreen) {
			const webrtc::DesktopVector & top_left = cFrame->top_left();
			const webrtc::DesktopSize & size = cFrame->size();
			const int32_t border = GetSystemMetrics(SM_CXPADDEDBORDER);

			int32_t x = border;
			int32_t y = top_left.y() < 0 ? -top_left.y() : 0;
			int32_t w = size.width() - x * 2;
			int32_t h = size.height() - (y + border);

			auto croppedFrame = std::make_unique<webrtc::BasicDesktopFrame>(webrtc::DesktopSize(w, h));
			croppedFrame->set_top_left(top_left.add(webrtc::DesktopVector(x, y)));
			croppedFrame->CopyPixelsFrom(*cFrame, webrtc::DesktopVector(x, y),
				webrtc::DesktopRect::MakeSize(croppedFrame->size()));

			cFrame = std::move(croppedFrame);
		}
#endif

		env->CallVoidMethod(callback, javaClass->onCaptureResult,
			jresult.get(), DesktopFrame::toJava(env, cFrame.get()).get());
	}

	DesktopCaptureCallback::JavaDesktopCaptureCallbackClass::JavaDesktopCaptureCallbackClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_DESKTOP"DesktopCaptureCallback");

		onCaptureResult = GetMethod(env, cls, "onCaptureResult", "(L" PKG_DESKTOP "DesktopCapturer$Result;L" PKG_DESKTOP "DesktopFrame;)V");
	}
}