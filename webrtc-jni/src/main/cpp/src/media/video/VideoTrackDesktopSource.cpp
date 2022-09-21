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

#include "media/video/VideoTrackDesktopSource.h"
#include "Exception.h"

#include "api/video/i420_buffer.h"
#include "libyuv/convert.h"
#include "modules/desktop_capture/cropping_window_capturer.h"
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/video_capture/video_capture_factory.h"
#include "third_party/libyuv/include/libyuv/video_common.h"
#include "system_wrappers/include/sleep.h"

#include "modules/desktop_capture/desktop_capturer.h"
#include "modules/desktop_capture/desktop_and_cursor_composer.h"
#include "modules/desktop_capture/mouse_cursor_monitor.h"

namespace jni
{
	VideoTrackDesktopSource::VideoTrackDesktopSource() :
		AdaptedVideoTrackSource(),
		frameRate(20),
		isCapturing(false),
		focusSelectedSource(true),
		sourceState(kInitializing),
		sourceId(-1),
		sourceIsWindow(false)
	{
	}

	VideoTrackDesktopSource::~VideoTrackDesktopSource()
	{
		stop();
	}

	void VideoTrackDesktopSource::setSourceId(webrtc::DesktopCapturer::SourceId source, bool isWindow)
	{
		this->sourceId = source;
		this->sourceIsWindow = isWindow;
	}

	void VideoTrackDesktopSource::setFrameRate(const uint16_t frameRate)
	{
		this->frameRate = frameRate;
	}

	void VideoTrackDesktopSource::setFocusSelectedSource(bool focus)
	{
		this->focusSelectedSource = focus;
	}

	void VideoTrackDesktopSource::start()
	{
		isCapturing = true;

		captureThread = rtc::Thread::Create();
		captureThread->Start();
		captureThread->PostTask(RTC_FROM_HERE, [&] { capture(); });
	}

	void VideoTrackDesktopSource::stop()
	{
		if (isCapturing) {
			isCapturing = false;

			captureThread->Stop();
			captureThread.reset();
		}
	}

	bool VideoTrackDesktopSource::is_screencast() const {
		return true;
	}

	absl::optional<bool> VideoTrackDesktopSource::needs_denoising() const {
		return false;
	}

	webrtc::MediaSourceInterface::SourceState VideoTrackDesktopSource::state() const {
		return sourceState;
	}

	bool VideoTrackDesktopSource::remote() const {
		return false;
	}

	void VideoTrackDesktopSource::OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame)
	{
		if (result != webrtc::DesktopCapturer::Result::SUCCESS) {
			return;
		}

		int width = frame->size().width();
		int height = frame->size().height();
		int64_t time = rtc::TimeMicros();

		int adapted_width;
		int adapted_height;
		int crop_width;
		int crop_height;
		int crop_x;
		int crop_y;

		if (!AdaptFrame(width, height, time, &adapted_width, &adapted_height, &crop_width, &crop_height, &crop_x, &crop_y)) {
			// Drop frame in order to respect frame rate constraint.
			return;
		}

		if (!buffer || buffer->width() != width || buffer->height() != height) {
			buffer = webrtc::I420Buffer::Create(width, height);
		}

		const int conversionResult = libyuv::ARGBToI420(frame->data(), frame->stride(),
			buffer->MutableDataY(), buffer->StrideY(),
			buffer->MutableDataU(), buffer->StrideU(),
			buffer->MutableDataV(), buffer->StrideV(),
			width, height);

		if (conversionResult >= 0) {
			if (adapted_width != width || adapted_height != height) {
				// Video adapter has requested a down-scale. Allocate a new buffer and return scaled version.
				rtc::scoped_refptr<webrtc::I420Buffer> scaled_buffer = webrtc::I420Buffer::Create(adapted_width, adapted_height);

				scaled_buffer->ScaleFrom(*buffer);

				OnFrame(webrtc::VideoFrame::Builder()
					.set_video_frame_buffer(scaled_buffer)
					.set_rotation(webrtc::kVideoRotation_0)
					.set_timestamp_us(time)
					.build());
			}
			else {
				// No adaptations needed, just return the frame as is.
				OnFrame(webrtc::VideoFrame::Builder()
					.set_video_frame_buffer(buffer)
					.set_rotation(webrtc::kVideoRotation_0)
					.set_timestamp_us(time)
					.build());
			}
		}
	}

	void VideoTrackDesktopSource::capture()
	{
		auto options = webrtc::DesktopCaptureOptions::CreateDefault();
		// Enable desktop effects.
		options.set_disable_effects(false);

#if defined(WEBRTC_WIN)
		options.set_allow_directx_capturer(true);
		options.set_allow_use_magnification_api(true);
#endif

		std::unique_ptr<webrtc::DesktopCapturer> capturer;

		if (sourceIsWindow) {
			capturer.reset(new webrtc::DesktopAndCursorComposer(
				webrtc::DesktopCapturer::CreateWindowCapturer(options),
				options));
		}
		else {
			capturer.reset(new webrtc::DesktopAndCursorComposer(
				webrtc::DesktopCapturer::CreateScreenCapturer(options),
				options));
		}

		if (!capturer->SelectSource(sourceId)) {
			return;
		}

		capturer->Start(this);

		if (focusSelectedSource) {
			capturer->FocusOnSelectedSource();
		}

		sourceState = kLive;

		int msPerFrame = 1000 / frameRate;

		while (isCapturing) {
			capturer->CaptureFrame();

			webrtc::SleepMs(msPerFrame);
		}

		sourceState = kEnded;
	}
}