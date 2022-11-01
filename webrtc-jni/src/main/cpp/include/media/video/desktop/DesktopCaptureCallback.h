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

#ifndef JNI_WEBRTC_MEDIA_DESKTOP_CAPTURE_CALLBACK_H_
#define JNI_WEBRTC_MEDIA_DESKTOP_CAPTURE_CALLBACK_H_

#include "api/VideoFrame.h"
#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video/i420_buffer.h"
#include "modules/desktop_capture/desktop_capturer.h"

#include <jni.h>

namespace jni
{
	class DesktopCaptureCallback : public webrtc::DesktopCapturer::Callback
	{
		public:
			DesktopCaptureCallback(JNIEnv * env, const JavaGlobalRef<jobject> & callback);
			~DesktopCaptureCallback() = default;

			// DesktopCapturer::Callback implementation.
			void OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame) override;

		private:
			class JavaDesktopCaptureCallbackClass : public JavaClass
			{
				public:
					explicit JavaDesktopCaptureCallbackClass(JNIEnv * env);

					jmethodID onCaptureResult;
			};

		private:
			JavaGlobalRef<jobject> callback;

			const std::shared_ptr<JavaDesktopCaptureCallbackClass> javaClass;
			const std::shared_ptr<JavaVideoFrameClass> javaFrameClass;

			rtc::scoped_refptr<webrtc::I420Buffer> i420Buffer;
	};
}

#endif