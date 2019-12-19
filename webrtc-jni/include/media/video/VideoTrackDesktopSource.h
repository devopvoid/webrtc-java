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

#include "api/create_peerconnection_factory.h"
#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "api/video/video_sink_interface.h"
#include "pc/video_track_source.h"
#include "media/base/video_adapter.h"
#include "media/base/video_broadcaster.h"
#include "modules/desktop_capture/desktop_capturer.h"

#include <thread>

namespace jni
{
	class VideoTrackDesktopSource : public webrtc::VideoTrackSource, public webrtc::DesktopCapturer::Callback
	{
		public:
			VideoTrackDesktopSource();
			~VideoTrackDesktopSource();

			void setDesktopCapturer(webrtc::DesktopCapturer * capturer);
			void setFrameRate(const uint16_t frameRate);

			void start();
			void stop();

			// VideoSourceInterface implementation.
			void AddOrUpdateSink(rtc::VideoSinkInterface<webrtc::VideoFrame> * sink, const rtc::VideoSinkWants & wants) override;
			void RemoveSink(rtc::VideoSinkInterface<webrtc::VideoFrame> * sink) override;

			// VideoTrackSource implementation.
			rtc::VideoSourceInterface<webrtc::VideoFrame> * source() override;

			// DesktopCapturer::Callback implementation.
			void OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame) override;

		private:
			void capture();
			void updateVideoAdapter();

		private:
			uint16_t frameRate;
			bool isCapturing;

			std::thread capturethread;

			webrtc::DesktopCapturer * capturer;
			rtc::scoped_refptr<webrtc::I420Buffer> buffer;
			rtc::VideoBroadcaster broadcaster;
			cricket::VideoAdapter videoAdapter;
	};
}

#endif