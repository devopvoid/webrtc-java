/*
 * Copyright 2026 Alex Andres
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

#include "media/video/desktop/macos/ScreenCaptureKitWindowCapturer.h"

#include "modules/desktop_capture/desktop_frame.h"
#include "modules/desktop_capture/mac/desktop_frame_iosurface.h"
#include "modules/desktop_capture/mac/window_list_utils.h"
#include "modules/desktop_capture/shared_desktop_frame.h"
#include "rtc_base/logging.h"

#import <AppKit/AppKit.h>
#import <CoreMedia/CoreMedia.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>

#include <unistd.h>

namespace jni
{
	class ScreenCaptureKitWindowCapturer;
}

// Receives the frames of an SCStream. ScreenCaptureKit may still call it
// while the stream stops, so the capturer detaches itself as its owner, under
// the same lock the callbacks take, before it goes away.
API_AVAILABLE(macos(14.0))
@interface WindowCaptureReceiver : NSObject <SCStreamOutput, SCStreamDelegate> {
@public
	jni::ScreenCaptureKitWindowCapturer * owner;
}
@end

namespace jni
{
	namespace
	{
		// How long a ScreenCaptureKit request may take before the capturer
		// falls back to WebRTC's window capturer.
		constexpr std::chrono::seconds kRequestTimeout(10);

		std::string Describe(NSError * error)
		{
			if (!error) {
				return "no error given";
			}

			const char * reason = error.localizedDescription.UTF8String;

			return "error " + std::to_string(error.code) + (reason ? std::string(": ") + reason : std::string());
		}

		// Returns the process that owns the window, or 0 once it is gone.
		pid_t GetWindowOwner(CGWindowID window)
		{
			// The array holds the ID itself, as a pointer-sized value.
			const void * id = reinterpret_cast<const void *>(static_cast<uintptr_t>(window));
			CFArrayRef ids = CFArrayCreate(nullptr, &id, 1, nullptr);
			CFArrayRef windows = ids ? CGWindowListCreateDescriptionFromArray(ids) : nullptr;
			pid_t owner = 0;

			if (windows && CFArrayGetCount(windows) > 0) {
				owner = webrtc::GetWindowOwnerPid(static_cast<CFDictionaryRef>(CFArrayGetValueAtIndex(windows, 0)));
			}

			if (windows) {
				CFRelease(windows);
			}
			if (ids) {
				CFRelease(ids);
			}

			return owner;
		}

		// The outcome of an asynchronous ScreenCaptureKit request. Its handler
		// holds it, so it outlives a caller that gave up waiting.
		struct API_AVAILABLE(macos(14.0)) Request
		{
			std::mutex mutex;
			std::condition_variable condition;
			bool done = false;
			std::atomic<bool> abandoned { false };
			NSError * error = nil;
			SCShareableContent * content = nil;

			~Request()
			{
				[error release];
				[content release];
			}

			void Finish(NSError * e, SCShareableContent * c = nil) noexcept
			{
				try {
					std::lock_guard<std::mutex> lock(mutex);

					error = [e retain];
					content = [c retain];
					done = true;

					condition.notify_all();
				}
				catch (...) {
					// The waiting caller times out; never unwind into the OS.
				}
			}

			// Returns false if the request did not finish in time.
			bool Wait()
			{
				std::unique_lock<std::mutex> lock(mutex);

				if (!condition.wait_for(lock, kRequestTimeout, [this]() { return done; })) {
					abandoned = true;
					return false;
				}

				return true;
			}
		};
	}

	class API_AVAILABLE(macos(14.0)) ScreenCaptureKitWindowCapturer final : public webrtc::DesktopCapturer
	{
		public:
			explicit ScreenCaptureKitWindowCapturer(const webrtc::DesktopCaptureOptions & options);
			~ScreenCaptureKitWindowCapturer() override;

			// DesktopCapturer implementation. All run on the caller's thread.
			void Start(Callback * callback) override;
			void SetMaxFrameRate(uint32_t maxFrameRate) override;
			void CaptureFrame() override;
			bool GetSourceList(SourceList * sources) override;
			bool SelectSource(SourceId id) override;
			bool FocusOnSelectedSource() override;
			bool IsOccluded(const webrtc::DesktopVector & pos) override;

			// Called by the receiver, on the stream's queue.
			void OnSample(CMSampleBufferRef sample);
			void OnStopped(NSError * error);

		private:
			// Streams the selected window, or has the fallback capture it.
			void StartCapture();
			// Starts streaming the selected window. On failure, returns false
			// with the reason set and nothing left running.
			bool StartStream(std::string * reason);
			void StopStream();
			void StartFallback();
			// Returns a retained configuration for an output of the given size.
			SCStreamConfiguration * CreateConfiguration(const webrtc::DesktopSize & size) const;

			const webrtc::DesktopCaptureOptions options;

			Callback * callback;
			CGWindowID window;
			uint32_t maxFrameRate;

			SCStream * stream;
			WindowCaptureReceiver * receiver;
			dispatch_queue_t queue;
			// The output size the stream has been asked for, in pixels.
			webrtc::DesktopSize streamSize;

			// WebRTC's window capturer, once ScreenCaptureKit could not start.
			std::unique_ptr<webrtc::DesktopCapturer> fallback;

			// Guards the members below, which the stream's queue writes.
			std::mutex mutex;
			std::unique_ptr<webrtc::SharedDesktopFrame> latestFrame;
			bool frameIsNew;
			bool streamStopped;
			// The output size the window's content needs to fit unscaled.
			webrtc::DesktopSize neededSize;
	};

	ScreenCaptureKitWindowCapturer::ScreenCaptureKitWindowCapturer(const webrtc::DesktopCaptureOptions & options) :
		options(options),
		callback(nullptr),
		window(kCGNullWindowID),
		maxFrameRate(0),
		stream(nil),
		receiver(nil),
		queue(nullptr),
		frameIsNew(false),
		streamStopped(false)
	{
	}

	ScreenCaptureKitWindowCapturer::~ScreenCaptureKitWindowCapturer()
	{
		StopStream();
	}

	void ScreenCaptureKitWindowCapturer::Start(Callback * callback)
	{
		this->callback = callback;

		StartCapture();
	}

	void ScreenCaptureKitWindowCapturer::SetMaxFrameRate(uint32_t maxFrameRate)
	{
		this->maxFrameRate = maxFrameRate;

		if (fallback) {
			fallback->SetMaxFrameRate(maxFrameRate);
		}
		else if (stream) {
			SCStreamConfiguration * config = CreateConfiguration(streamSize);

			[stream updateConfiguration:config completionHandler:nil];
			[config release];
		}
	}

	void ScreenCaptureKitWindowCapturer::CaptureFrame()
	{
		if (fallback) {
			fallback->CaptureFrame();
			return;
		}

		if (!stream || GetWindowOwner(window) == 0) {
			// Not capturing, or the window was closed. For a closed window,
			// ScreenCaptureKit also stops the stream.
			callback->OnCaptureResult(Result::ERROR_PERMANENT, nullptr);
			return;
		}

		std::unique_ptr<webrtc::DesktopFrame> frame;
		webrtc::DesktopSize size;
		bool stopped;

		{
			std::lock_guard<std::mutex> lock(mutex);

			stopped = streamStopped;
			size = neededSize;

			if (latestFrame) {
				frame = latestFrame->Share();

				if (!frameIsNew) {
					frame->mutable_updated_region()->Clear();
				}

				frameIsNew = false;
			}
		}

		if (stopped) {
			callback->OnCaptureResult(Result::ERROR_PERMANENT, nullptr);
			return;
		}

		if (!size.is_empty() && !size.equals(streamSize)) {
			// The window was resized. Until the stream follows, its frames
			// show the window scaled down or with a margin, which the
			// receiver crops.
			RTC_LOG(LS_INFO) << "ScreenCaptureKit window capture: output " << streamSize.width() << "x"
				<< streamSize.height() << " -> " << size.width() << "x" << size.height();

			SCStreamConfiguration * config = CreateConfiguration(size);

			[stream updateConfiguration:config completionHandler:nil];
			[config release];

			streamSize = size;
		}

		if (frame) {
			callback->OnCaptureResult(Result::SUCCESS, std::move(frame));
		}
		else {
			callback->OnCaptureResult(Result::ERROR_TEMPORARY, nullptr);
		}
	}

	bool ScreenCaptureKitWindowCapturer::GetSourceList(SourceList * sources)
	{
		// The same list as WebRTC's window capturer, so that its IDs select
		// the same windows.
		return webrtc::GetWindowList(sources, true, true);
	}

	bool ScreenCaptureKitWindowCapturer::SelectSource(SourceId id)
	{
		if (GetWindowOwner(static_cast<CGWindowID>(id)) == 0) {
			return false;
		}

		window = static_cast<CGWindowID>(id);

		if (fallback) {
			return fallback->SelectSource(id);
		}

		if (stream) {
			StopStream();
			StartCapture();
		}

		return true;
	}

	bool ScreenCaptureKitWindowCapturer::FocusOnSelectedSource()
	{
		if (fallback) {
			return fallback->FocusOnSelectedSource();
		}

		const pid_t pid = GetWindowOwner(window);

		if (pid == 0) {
			return false;
		}

		@autoreleasepool {
			// Like WebRTC's window capturer, this brings the whole application
			// to the front, not only the window.
			NSRunningApplication * app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];

			return app && [app activateWithOptions:0];
		}
	}

	bool ScreenCaptureKitWindowCapturer::IsOccluded(const webrtc::DesktopVector & pos)
	{
		// ScreenCaptureKit draws the cursor itself, only where the window is
		// visible, so only the fallback needs the composer to know.
		return fallback ? fallback->IsOccluded(pos) : false;
	}

	void ScreenCaptureKitWindowCapturer::OnSample(CMSampleBufferRef sample)
	{
		CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);

		if (!attachments || CFArrayGetCount(attachments) < 1) {
			return;
		}

		NSDictionary * info = (__bridge NSDictionary *) CFArrayGetValueAtIndex(attachments, 0);
		NSNumber * status = info[SCStreamFrameInfoStatus];

		if (!status) {
			return;
		}

		const SCFrameStatus frameStatus = static_cast<SCFrameStatus>(status.integerValue);

		if (frameStatus != SCFrameStatusComplete && frameStatus != SCFrameStatusStarted) {
			// Idle when nothing changed; blank or suspended while the window
			// cannot be seen, as when minimized. The last frame stays.
			return;
		}

		CVPixelBufferRef pixels = CMSampleBufferGetImageBuffer(sample);
		IOSurfaceRef surface = pixels ? CVPixelBufferGetIOSurface(pixels) : nullptr;

		if (!surface) {
			return;
		}

		// Pixels per point of the display the window is on.
		NSNumber * scaleFactor = info[SCStreamFrameInfoScaleFactor];
		// How far the window had to be scaled down to fit the output.
		NSNumber * contentScale = info[SCStreamFrameInfoContentScale];

		const double scale = scaleFactor && scaleFactor.doubleValue > 0 ? scaleFactor.doubleValue : 1;
		const double shrink = contentScale && contentScale.doubleValue > 0 ? contentScale.doubleValue : 1;

		const int surfaceWidth = static_cast<int>(IOSurfaceGetWidth(surface));
		const int surfaceHeight = static_cast<int>(IOSurfaceGetHeight(surface));

		// Where the window's content lies in the surface, in points.
		CGRect content = CGRectMake(0, 0, surfaceWidth / scale, surfaceHeight / scale);
		CFDictionaryRef contentRect = (__bridge CFDictionaryRef) info[SCStreamFrameInfoContentRect];

		if (contentRect) {
			CGRectMakeWithDictionaryRepresentation(contentRect, &content);
		}

		// The same in pixels, kept inside the surface: DesktopFrameIOSurface
		// stops the process on a crop that leaves it.
		const int x = std::clamp(static_cast<int>(std::lround(content.origin.x * scale)), 0, surfaceWidth);
		const int y = std::clamp(static_cast<int>(std::lround(content.origin.y * scale)), 0, surfaceHeight);
		const int width = std::clamp(static_cast<int>(std::lround(content.size.width * scale)), 0, surfaceWidth - x);
		const int height = std::clamp(static_cast<int>(std::lround(content.size.height * scale)), 0, surfaceHeight - y);

		if (width <= 0 || height <= 0) {
			return;
		}

		std::unique_ptr<webrtc::DesktopFrame> frame = webrtc::DesktopFrameIOSurface::Wrap(
			webrtc::ScopedCFTypeRef<IOSurfaceRef>(surface, webrtc::RetainPolicy::RETAIN),
			CGRectMake(x, y, width, height));

		if (!frame) {
			return;
		}

		const int dpi = static_cast<int>(std::lround(webrtc::kStandardDPI * scale));

		frame->set_dpi(webrtc::DesktopVector(dpi, dpi));
		// Keeps DesktopAndCursorComposer from drawing a second cursor.
		frame->set_may_contain_cursor(true);
		frame->mutable_updated_region()->SetRect(webrtc::DesktopRect::MakeSize(frame->size()));

		// The output the whole window needs, unscaled: its size on screen in
		// pixels. The output grows for a window that was scaled down to fit
		// it, and shrinks for one that left a margin.
		webrtc::DesktopSize needed(
			static_cast<int32_t>(std::lround(width / shrink)),
			static_cast<int32_t>(std::lround(height / shrink)));

		CGRect screen = CGRectNull;
		CFDictionaryRef screenRect = (__bridge CFDictionaryRef) info[SCStreamFrameInfoScreenRect];

		if (screenRect && CGRectMakeWithDictionaryRepresentation(screenRect, &screen) &&
			screen.size.width > 0 && screen.size.height > 0) {
			// Exact, where the content's size was rounded when scaled down.
			needed.set(
				static_cast<int32_t>(std::lround(screen.size.width * scale)),
				static_cast<int32_t>(std::lround(screen.size.height * scale)));
		}

		std::lock_guard<std::mutex> lock(mutex);

		latestFrame = webrtc::SharedDesktopFrame::Wrap(std::move(frame));
		frameIsNew = true;
		neededSize = needed;
	}

	void ScreenCaptureKitWindowCapturer::OnStopped(NSError * error)
	{
		RTC_LOG(LS_WARNING) << "ScreenCaptureKit window capture stopped: " << Describe(error);

		std::lock_guard<std::mutex> lock(mutex);

		streamStopped = true;
	}

	void ScreenCaptureKitWindowCapturer::StartCapture()
	{
		if (GetWindowOwner(window) == getpid()) {
			// A stream keeps a window of its own process alive after the
			// window is closed, so the capture would never end. This process
			// may capture its own windows without screen recording permission.
			RTC_LOG(LS_INFO) << "Window " << window << " belongs to this process; capturing it with "
				"CGWindowListCreateImage";

			StartFallback();
			return;
		}

		std::string reason;

		if (!StartStream(&reason)) {
			RTC_LOG(LS_WARNING) << "ScreenCaptureKit cannot capture window " << window << " (" << reason
				<< "); capturing it with CGWindowListCreateImage";

			StartFallback();
		}
	}

	bool ScreenCaptureKitWindowCapturer::StartStream(std::string * reason)
	{
		@autoreleasepool {
			auto shareable = std::make_shared<Request>();

			// Off-screen windows too, such as minimized ones or those on
			// another Space.
			[SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:NO
				completionHandler:^(SCShareableContent * content, NSError * error) {
					shareable->Finish(error, content);
				}];

			if (!shareable->Wait()) {
				*reason = "listing shareable content timed out";
				return false;
			}
			if (!shareable->content) {
				*reason = "no shareable content, " + Describe(shareable->error);
				return false;
			}

			SCWindow * target = nil;

			for (SCWindow * candidate in shareable->content.windows) {
				if (candidate.windowID == window) {
					target = candidate;
					break;
				}
			}

			if (!target) {
				*reason = "the window is not shareable";
				return false;
			}

			SCContentFilter * filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:target];
			const double scale = filter.pointPixelScale > 0 ? filter.pointPixelScale : 1;

			streamSize = webrtc::DesktopSize(
				static_cast<int32_t>(std::lround(filter.contentRect.size.width * scale)),
				static_cast<int32_t>(std::lround(filter.contentRect.size.height * scale)));

			if (streamSize.is_empty()) {
				[filter release];

				*reason = "the window has no size";
				return false;
			}

			SCStreamConfiguration * config = CreateConfiguration(streamSize);

			receiver = [[WindowCaptureReceiver alloc] init];
			receiver->owner = this;
			queue = dispatch_queue_create("webrtc-window-capture", DISPATCH_QUEUE_SERIAL);
			stream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:receiver];

			[filter release];
			[config release];

			NSError * error = nil;

			if (![stream addStreamOutput:receiver type:SCStreamOutputTypeScreen sampleHandlerQueue:queue error:&error]) {
				*reason = "cannot add the stream output, " + Describe(error);

				StopStream();
				return false;
			}

			auto started = std::make_shared<Request>();
			SCStream * starting = stream;

			[stream startCaptureWithCompletionHandler:^(NSError * startError) {
				// A start that finishes after the caller gave up must not
				// leave the window captured unseen.
				if (started->abandoned.load() && !startError) {
					[starting stopCaptureWithCompletionHandler:nil];
				}

				started->Finish(startError);
			}];

			if (!started->Wait()) {
				*reason = "starting the stream timed out";

				StopStream();
				return false;
			}
			if (started->error) {
				*reason = "cannot start the stream, " + Describe(started->error);

				StopStream();
				return false;
			}

			RTC_LOG(LS_INFO) << "ScreenCaptureKit window capture: window " << window << ", output "
				<< streamSize.width() << "x" << streamSize.height();

			return true;
		}
	}

	void ScreenCaptureKitWindowCapturer::StopStream()
	{
		@autoreleasepool {
			if (receiver) {
				@synchronized(receiver) {
					receiver->owner = nullptr;
				}
			}

			if (stream) {
				SCStream * stopping = stream;
				WindowCaptureReceiver * stoppingReceiver = receiver;
				dispatch_queue_t stoppingQueue = queue;

				// The handler keeps the stream, its receiver and its queue
				// until ScreenCaptureKit has stopped using them.
				[stream stopCaptureWithCompletionHandler:^(NSError * error) {
					[stopping self];
					[stoppingReceiver self];
					(void) stoppingQueue;
				}];
			}

			[stream release];
			[receiver release];

			if (queue) {
				dispatch_release(queue);
			}

			stream = nil;
			receiver = nil;
			queue = nullptr;

			std::lock_guard<std::mutex> lock(mutex);

			latestFrame.reset();
			frameIsNew = false;
			streamStopped = false;
			neededSize = webrtc::DesktopSize();
		}
	}

	void ScreenCaptureKitWindowCapturer::StartFallback()
	{
		fallback = webrtc::DesktopCapturer::CreateWindowCapturer(options);

		if (!fallback) {
			return;
		}

		fallback->SelectSource(window);

		if (maxFrameRate > 0) {
			fallback->SetMaxFrameRate(maxFrameRate);
		}

		fallback->Start(callback);
	}

	SCStreamConfiguration * ScreenCaptureKitWindowCapturer::CreateConfiguration(const webrtc::DesktopSize & size) const
	{
		SCStreamConfiguration * config = [[SCStreamConfiguration alloc] init];

		config.width = size.width();
		config.height = size.height();
		config.pixelFormat = kCVPixelFormatType_32BGRA;
		config.colorSpaceName = kCGColorSpaceSRGB;
		// A window larger than the output is scaled down, never up; the next
		// CaptureFrame() then asks for an output it fits.
		config.scalesToFit = NO;
		// Without the shadow, as CGWindowListCreateImage with
		// kCGWindowImageBoundsIgnoreFraming.
		config.ignoreShadowsSingleWindow = YES;
		config.showsCursor = YES;
		config.minimumFrameInterval = maxFrameRate > 0
			? CMTimeMake(1, static_cast<int32_t>(maxFrameRate))
			: kCMTimeZero;

		return config;
	}

	std::unique_ptr<webrtc::DesktopCapturer> CreateScreenCaptureKitWindowCapturer(const webrtc::DesktopCaptureOptions & options)
	{
		if (@available(macOS 14.0, *)) {
			return std::make_unique<ScreenCaptureKitWindowCapturer>(options);
		}

		return nullptr;
	}
}

@implementation WindowCaptureReceiver

- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type
{
	if (type != SCStreamOutputTypeScreen || !CMSampleBufferIsValid(sample)) {
		return;
	}

	@synchronized(self) {
		try {
			if (owner) {
				owner->OnSample(sample);
			}
		}
		catch (...) {
			// Drops the frame; never unwind into ScreenCaptureKit.
		}
	}
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error
{
	@synchronized(self) {
		try {
			if (owner) {
				owner->OnStopped(error);
			}
		}
		catch (...) {
		}
	}
}

@end
