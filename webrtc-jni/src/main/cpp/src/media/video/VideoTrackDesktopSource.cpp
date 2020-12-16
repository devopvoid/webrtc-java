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
#include "modules/desktop_capture/desktop_capture_options.h"
#include "modules/video_capture/video_capture_factory.h"
#include "third_party/libyuv/include/libyuv/video_common.h"

#include <chrono>

namespace jni
{
	VideoTrackDesktopSource::VideoTrackDesktopSource() :
		VideoTrackSource(/*remote=*/false),
		frameRate(15),
		isCapturing(false),
		capturer(nullptr)
	{
	}

	VideoTrackDesktopSource::~VideoTrackDesktopSource()
	{
		stop();
	}

	void VideoTrackDesktopSource::setDesktopCapturer(webrtc::DesktopCapturer * capturer)
	{
		bool restart = isCapturing;

		if (capturer != nullptr) {
			stop();
		}

		this->capturer = capturer;

		if (restart) {
			start();
		}
	}

	void VideoTrackDesktopSource::setFrameRate(const uint16_t frameRate)
	{
		this->frameRate = frameRate;
	}

	void VideoTrackDesktopSource::start()
	{
		if (capturer == nullptr) {
			throw Exception("DesktopCapturer is null");
		}

		capturer->Start(this);
		
		isCapturing = true;

		capturethread = std::thread(&VideoTrackDesktopSource::capture, this);
	}

	void VideoTrackDesktopSource::stop()
	{
		isCapturing = false;

		if (capturethread.joinable()) {
			capturethread.join();
		}
	}

	void VideoTrackDesktopSource::AddOrUpdateSink(rtc::VideoSinkInterface<webrtc::VideoFrame> * sink, const rtc::VideoSinkWants & wants)
	{
		broadcaster.AddOrUpdateSink(sink, wants);

		updateVideoAdapter();
	}

	void VideoTrackDesktopSource::RemoveSink(rtc::VideoSinkInterface<webrtc::VideoFrame> * sink)
	{
		broadcaster.RemoveSink(sink);

		updateVideoAdapter();
	}

	rtc::VideoSourceInterface<webrtc::VideoFrame> * VideoTrackDesktopSource::source()
	{
		return this;
	}

	void VideoTrackDesktopSource::OnCaptureResult(webrtc::DesktopCapturer::Result result, std::unique_ptr<webrtc::DesktopFrame> frame)
	{
		if (result == webrtc::DesktopCapturer::Result::SUCCESS) {
			int width = frame->rect().width();
			int height = frame->rect().height();

			int croppedWidth = 0;
			int croppedHeight = 0;
			int outWidth = 0;
			int outHeight = 0;

			int64_t timestamp = rtc::TimeMicros();

			if (!videoAdapter.AdaptFrameResolution(width, height, timestamp * 1000,
				&croppedWidth, &croppedHeight, &outWidth, &outHeight)) {
				// Drop frame in order to respect frame rate constraint.
				return;
			}

			if (!buffer || buffer->width() != width || buffer->height() != height) {
				buffer = webrtc::I420Buffer::Create(width, height);
			}

			const int conversionResult = libyuv::ConvertToI420(
				frame->data(),
				static_cast<size_t>(frame->stride()) * webrtc::DesktopFrame::kBytesPerPixel,
				buffer->MutableDataY(), buffer->StrideY(),
				buffer->MutableDataU(), buffer->StrideU(),
				buffer->MutableDataV(), buffer->StrideV(),
				0, 0,
				width, height,
				width, height,
				libyuv::kRotate0, libyuv::FOURCC_ARGB);

			if (conversionResult >= 0) {
				if (outWidth != width || outHeight != height) {
					// Video adapter has requested a down-scale. Allocate a new buffer and return scaled version.
					rtc::scoped_refptr<webrtc::I420Buffer> scaledBuffer = webrtc::I420Buffer::Create(outWidth, outHeight);

					scaledBuffer->ScaleFrom(*buffer);

					broadcaster.OnFrame(webrtc::VideoFrame::Builder()
						.set_video_frame_buffer(scaledBuffer)
						.set_rotation(webrtc::kVideoRotation_0)
						.set_timestamp_us(timestamp)
						.build());
				}
				else {
					// No adaptations needed, just return the frame as is.
					webrtc::VideoFrame videoFrame(buffer, webrtc::VideoRotation::kVideoRotation_0, timestamp);

					broadcaster.OnFrame(videoFrame);
				}
			}
		}
		else if (result == webrtc::DesktopCapturer::Result::ERROR_TEMPORARY) {
			RTC_LOG(LS_ERROR) << "Capture frame failed";
		}
		else if (result == webrtc::DesktopCapturer::Result::ERROR_PERMANENT) {
			RTC_LOG(LS_ERROR) << "Capture frame failed permanently";
			isCapturing = false;
		}
	}

	void VideoTrackDesktopSource::capture()
	{
		RTC_LOG(INFO) << "Start capture";

		auto framerate = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::duration<double>(1.0 / frameRate));
		auto nextFrame = std::chrono::high_resolution_clock::now();

		while (isCapturing) {
			capturer->CaptureFrame();

			nextFrame += framerate;

			std::this_thread::sleep_until(nextFrame);
		}

		RTC_LOG(INFO) << "Stop capture";
	}

	void VideoTrackDesktopSource::updateVideoAdapter()
	{
		rtc::VideoSinkWants wants = broadcaster.wants();

		videoAdapter.OnOutputFormatRequest(std::make_pair(0, 0), wants.max_pixel_count, wants.max_framerate_fps);
	}
}