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

#include "libyuv/convert.h"
#include "libyuv/video_common.h"
#include "modules/desktop_capture/desktop_frame.h"
#include "rtc_base/logging.h"
#include "rtc_base/time_utils.h"

#if defined(WEBRTC_WIN)
#include "rtc_base/win32.h"
#endif

namespace jni
{
	DesktopCaptureCallback::DesktopCaptureCallback(JNIEnv * env, const JavaGlobalRef<jobject> & callback) :
		callback(callback),
		javaClass(JavaClasses::get<JavaDesktopCaptureCallbackClass>(env)),
		javaFrameClass(JavaClasses::get<JavaVideoFrameClass>(env))
	{
	}

	void DesktopCaptureCallback::OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame)
	{
		JNIEnv * env = AttachCurrentThread();

		if (result != webrtc::DesktopCapturer::Result::SUCCESS) {
			return;
		}

		auto jresult = JavaEnums::toJava(env, result);

		int width = frame->size().width();
		int height = frame->size().height();

		int crop_x = 0;
		int crop_y = 0;
		int crop_w = width;
		int crop_h = height;

#if defined(WEBRTC_WIN)
		// Crop black window borders.
		bool fullscreen = frame->stride() == (frame->size().width() * webrtc::DesktopFrame::kBytesPerPixel);

		if (!fullscreen) {
			const webrtc::DesktopVector & top_left = frame->top_left();
			const int32_t border = GetSystemMetrics(SM_CXPADDEDBORDER);

			crop_x = border;
			crop_y = top_left.y() < 0 ? -top_left.y() : 0;
			crop_w = width - crop_x * 2;
			crop_h = height - (crop_y + border);
		}
#endif

		if (!i420Buffer || i420Buffer->width() != crop_w || i420Buffer->height() != crop_h) {
			i420Buffer = webrtc::I420Buffer::Create(crop_w, crop_h);
		}

		const int conversionResult = libyuv::ConvertToI420(
			frame->data(),
			0,
			i420Buffer->MutableDataY(), i420Buffer->StrideY(),
			i420Buffer->MutableDataU(), i420Buffer->StrideU(),
			i420Buffer->MutableDataV(), i420Buffer->StrideV(),
			crop_x, crop_y,
			frame->stride() / webrtc::DesktopFrame::kBytesPerPixel, i420Buffer->height(), crop_w, crop_h,
			libyuv::kRotate0,
			libyuv::FOURCC_ARGB);
		
		if (conversionResult < 0) {
			RTC_LOG(LS_ERROR) << "Failed to convert desktop frame to I420";
			return;
		}
		
		jint rotation = static_cast<jint>(webrtc::kVideoRotation_0);
		jlong timestamp = rtc::TimeMicros() * rtc::kNumNanosecsPerMicrosec;

		JavaLocalRef<jobject> jBuffer = I420Buffer::toJava(env, i420Buffer);
		jobject jFrame = env->NewObject(javaFrameClass->cls, javaFrameClass->ctor, jBuffer.get(), rotation, timestamp);

		env->CallVoidMethod(callback, javaClass->onCaptureResult, jresult.get(), jFrame);
		env->DeleteLocalRef(jBuffer);
		env->DeleteLocalRef(jFrame);
	}

	DesktopCaptureCallback::JavaDesktopCaptureCallbackClass::JavaDesktopCaptureCallbackClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_DESKTOP"DesktopCaptureCallback");

		onCaptureResult = GetMethod(env, cls, "onCaptureResult", "(L" PKG_DESKTOP "DesktopCapturer$Result;L" PKG_VIDEO "VideoFrame;)V");
	}
}