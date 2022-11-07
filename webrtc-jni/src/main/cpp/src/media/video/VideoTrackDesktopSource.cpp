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
#include "rtc_base/logging.h"
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

	void VideoTrackDesktopSource::setMaxFrameSize(webrtc::DesktopSize size)
	{
		this->maxFrameSize = size;
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

	void VideoTrackDesktopSource::terminate()
	{
		// Notify the track that we are permanently done.
		sourceState = kEnded;
		FireOnChanged();
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
			if (result == webrtc::DesktopCapturer::Result::ERROR_PERMANENT) {
				RTC_LOG(LS_ERROR) << "Permanent error capturing desktop frame. Stopping track.";

				terminate();
				stop();
			}
			
			return;
		}

		int width = frame->size().width();
		int height = frame->size().height();

		if (width == 1 && height == 1) {
			// Window has been minimized (hidden).
			if (lastFrame != nullptr) {
				process(lastFrame);
			}
		}
		else {
			// Copy current frame as backup, in order to show it when the window is minimized.
			lastFrame.reset(new webrtc::BasicDesktopFrame(webrtc::DesktopSize(width, height)));
			lastFrame->CopyPixelsFrom(frame->data(), frame->stride(), webrtc::DesktopRect::MakeWH(width, height));

			process(frame);
		}
	}

	void VideoTrackDesktopSource::process(std::unique_ptr<webrtc::DesktopFrame> & frame)
	{
		int64_t time = rtc::TimeMicros();

		int width = frame->size().width();
		int height = frame->size().height();
		
		int adapted_width;
		int adapted_height;

		int crop_x = 0;
		int crop_y = 0;
		int crop_w = width;
		int crop_h = height;

		if (!AdaptFrame(width, height, time, &adapted_width, &adapted_height, &crop_w, &crop_h, &crop_x, &crop_y)) {
			// Drop frame in order to respect frame rate constraint.
			return;
		}

#if defined(WEBRTC_WIN)
		// Crop black window borders.
		bool fullscreen = frame->stride() == (frame->size().width() * webrtc::DesktopFrame::kBytesPerPixel);

		if (!fullscreen) {
			const webrtc::DesktopVector& top_left = frame->top_left();
			const int32_t border = GetSystemMetrics(SM_CXPADDEDBORDER);

			crop_x = border;
			crop_y = top_left.y() < 0 ? -top_left.y() : 0;
			crop_w = width - crop_x * 2;
			crop_h = height - (crop_y + border);
		}
#endif

		if (!buffer || buffer->width() != crop_w || buffer->height() != crop_h) {
			buffer = webrtc::I420Buffer::Create(crop_w, crop_h);
		}

		const int conversionResult = libyuv::ConvertToI420(
			frame->data(),
			0,
			buffer->MutableDataY(), buffer->StrideY(),
			buffer->MutableDataU(), buffer->StrideU(),
			buffer->MutableDataV(), buffer->StrideV(),
			crop_x, crop_y,
			frame->stride() / webrtc::DesktopFrame::kBytesPerPixel, buffer->height(), crop_w, crop_h,
			libyuv::kRotate0,
			libyuv::FOURCC_ARGB);

		if (conversionResult >= 0) {
			if (!maxFrameSize.is_empty()) {
				// Adapt frame size to contraints.
				int max_width = maxFrameSize.width();
				int max_height = maxFrameSize.height();

				if (adapted_width > max_width) {
					double scale = max_width / (double)adapted_width;
					adapted_width = max_width;
					adapted_height = (int)(adapted_height * scale);
				}
				else if (adapted_height > max_height) {
					double scale = max_height / (double)adapted_height;
					adapted_width = (int)(adapted_width * scale);
					adapted_height = max_height;
				}
			}

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
			terminate();
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
	}
}