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
			// Propagate the failure to Java instead of silently dropping it —
			// callers waiting for a frame otherwise have to rely on timeouts.
			auto jerror = JavaEnums::toJava(env, result);
			env->CallVoidMethod(callback, javaClass->onCaptureResult, jerror.get(), nullptr);
			ExceptionCheck(env);
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
			// (src_width, src_height) must describe the FULL source frame, not the cropped
			// output. Passing i420Buffer->height() (== crop_h) here made libyuv's internal
			// bounds check (crop_y + crop_height <= src_height) fail with -1 whenever
			// crop_y > 0 — i.e. for every maximized window, whose frame sits at
			// top_left().y() == -border. Screen frames never hit this branch (exact stride,
			// fullscreen == true), which is why only window capture appeared broken.
			frame->stride() / webrtc::DesktopFrame::kBytesPerPixel, height, crop_w, crop_h,
			libyuv::kRotate0,
			libyuv::FOURCC_ARGB);

		if (conversionResult < 0) {
			RTC_LOG(LS_ERROR) << "Failed to convert desktop frame to I420";
			return;
		}

		jint rotation = static_cast<jint>(webrtc::kVideoRotation_0);
		jlong timestamp = webrtc::TimeMicros() * webrtc::kNumNanosecsPerMicrosec;

		webrtc::scoped_refptr<webrtc::I420Buffer> i420BufferCopy = webrtc::I420Buffer::Copy(*i420Buffer);
		i420BufferCopy->AddRef();

		JavaLocalRef<jobject> jBuffer = I420Buffer::toJava(env, i420BufferCopy);
		jobject jFrame = env->NewObject(javaFrameClass->cls, javaFrameClass->ctor, jBuffer.get(), rotation, timestamp);

		env->CallVoidMethod(callback, javaClass->onCaptureResult, jresult.get(), jFrame);

		ExceptionCheck(env);
		env->DeleteLocalRef(jBuffer);
		env->DeleteLocalRef(jFrame);
	}

	DesktopCaptureCallback::JavaDesktopCaptureCallbackClass::JavaDesktopCaptureCallbackClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_DESKTOP"DesktopCaptureCallback");

		onCaptureResult = GetMethod(env, cls, "onCaptureResult", "(L" PKG_DESKTOP "DesktopCapturer$Result;L" PKG_VIDEO "VideoFrame;)V");
	}
}
