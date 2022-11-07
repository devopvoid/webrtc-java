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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_TRACK_DESKTOP_SOURCE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_TRACK_DESKTOP_SOURCE_H_

#include "api/video/i420_buffer.h"
#include "media/base/adapted_video_track_source.h"
#include "modules/desktop_capture/desktop_capturer.h"
#include "rtc_base/thread.h"

namespace jni
{
	class VideoTrackDesktopSource : public rtc::AdaptedVideoTrackSource, public webrtc::DesktopCapturer::Callback
	{
		public:
			VideoTrackDesktopSource();
			~VideoTrackDesktopSource();

			void setSourceId(webrtc::DesktopCapturer::SourceId source, bool isWindow);
			void setFrameRate(const uint16_t frameRate);
			void setMaxFrameSize(webrtc::DesktopSize size);
			void setFocusSelectedSource(bool focus);

			void start();
			void stop();
			void terminate();

			// AdaptedVideoTrackSource implementation.
			virtual bool is_screencast() const override;
			virtual absl::optional<bool> needs_denoising() const override;
			SourceState state() const override;
			bool remote() const override;

			// DesktopCapturer::Callback implementation.
			void OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame) override;

		private:
			void capture();
			void process(std::unique_ptr<webrtc::DesktopFrame> & frame);

		private:
			uint16_t frameRate;
			bool isCapturing;
			bool focusSelectedSource;

			webrtc::DesktopSize maxFrameSize;

			webrtc::MediaSourceInterface::SourceState sourceState;

			webrtc::DesktopCapturer::SourceId sourceId;
			bool sourceIsWindow;

			std::unique_ptr<webrtc::DesktopFrame> lastFrame;

			std::unique_ptr<rtc::Thread> captureThread;

			rtc::scoped_refptr<webrtc::I420Buffer> buffer;
	};
}

#endif