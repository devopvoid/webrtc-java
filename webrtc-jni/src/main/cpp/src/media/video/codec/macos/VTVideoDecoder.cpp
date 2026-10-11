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

#include "media/video/codec/macos/VTVideoDecoder.h"
#include "media/video/codec/macos/VTCodecUtils.h"

#include "api/make_ref_counted.h"
#include "api/video/video_frame.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "sdk/objc/components/video_frame_buffer/RTCCVPixelBuffer.h"
#include "sdk/objc/native/src/objc_frame_buffer.h"

#include <atomic>

namespace jni
{
	namespace
	{
		// A decoder that fails this often in a row, each time on a frame
		// WebRTC then replaced by a key frame, is given up on.
		constexpr int kMaxConsecutiveErrors = 3;

		// How many sessions of codecs with a software decoder the process
		// keeps at a time. The media engine serves a limited number of
		// streams; a stream past the limit starts in software. The number is a
		// cautious guess. A codec without a software decoder has nowhere to
		// go, and asks VideoToolbox regardless.
		constexpr int kMaxSessions = 8;

		std::atomic<int> sessionCount(0);

		bool IsFullRange(CMVideoFormatDescriptionRef format)
		{
			CFTypeRef value = CMFormatDescriptionGetExtension(format, kCMFormatDescriptionExtension_FullRangeVideo);

			return value != nullptr && CFGetTypeID(value) == CFBooleanGetTypeID()
				&& CFBooleanGetValue(static_cast<CFBooleanRef>(value));
		}
	}

	VTVideoDecoder::VTVideoDecoder(const char * name, bool softwareFallback) :
		requireKeyFrame(false),
		name(name),
		softwareFallback(softwareFallback),
		callback(nullptr),
		session(nullptr),
		format(nullptr),
		fullRange(false),
		consecutiveErrors(0),
		sampleCount(0)
	{
	}

	VTVideoDecoder::~VTVideoDecoder()
	{
		DestroySession();
	}

	int32_t VTVideoDecoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VTVideoDecoder::Release()
	{
		DestroySession();

		requireKeyFrame = false;
		consecutiveErrors = 0;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo VTVideoDecoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = ImplementationName();
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * VTVideoDecoder::ImplementationName() const
	{
		return name;
	}

	bool VTVideoDecoder::SetFormat(CMVideoFormatDescriptionRef newFormat)
	{
		ScopedCF<CMVideoFormatDescriptionRef> scopedFormat(newFormat);

		if (session != nullptr && CMFormatDescriptionEqual(newFormat, format)) {
			return true;
		}

		// The pixel format of the output follows the range, so a session does
		// not outlast a change of it. Another size is not accepted in place
		// either, but a change of the colour space may be.
		if (session != nullptr && IsFullRange(newFormat) == fullRange
			&& VTDecompressionSessionCanAcceptFormatDescription(session, newFormat))
		{
			CFRelease(format);
			format = scopedFormat.release();

			return true;
		}

		// Whatever is still decoding finishes before the session goes.
		DestroySession();

		if (!CreateSession(newFormat)) {
			return false;
		}

		format = scopedFormat.release();

		return true;
	}

	bool VTVideoDecoder::HasSession() const
	{
		return session != nullptr;
	}

	CMVideoDimensions VTVideoDecoder::GetDimensions() const
	{
		if (format == nullptr) {
			return CMVideoDimensions { 0, 0 };
		}

		return CMVideoFormatDescriptionGetDimensions(format);
	}

	bool VTVideoDecoder::CreateSession(CMVideoFormatDescriptionRef newFormat)
	{
		if (softwareFallback && sessionCount.load() >= kMaxSessions) {
			RTC_LOG(LS_INFO) << name << ": too many sessions";
			return false;
		}

		const bool newFullRange = IsFullRange(newFormat);

		ScopedCF<CFMutableDictionaryRef> specification = CreateCFDictionary();
		ScopedCF<CFMutableDictionaryRef> attributes = CreateCFDictionary();
		ScopedCF<CFDictionaryRef> surfaceProperties(CFDictionaryCreate(kCFAllocatorDefault, nullptr, nullptr, 0,
			&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks));

		if (!specification || !attributes || !surfaceProperties) {
			return false;
		}

		// The output is 8 bit 4:2:0, in the range of the stream, so that the
		// samples reach WebRTC as the software decoder would give them.
		const int pixelFormat = newFullRange
			? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
			: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;

		if (!SetCFNumber(attributes.get(), kCVPixelBufferPixelFormatTypeKey, pixelFormat)) {
			return false;
		}

		// Only the hardware decoder: where there is none, or it is busy, the
		// session fails to create.
		CFDictionarySetValue(specification.get(), kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder,
			kCFBooleanTrue);
		CFDictionarySetValue(specification.get(), kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder,
			kCFBooleanTrue);

		CFDictionarySetValue(attributes.get(), kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties.get());

		VTDecompressionOutputCallbackRecord record = { OnOutput, this };
		OSStatus status = VTDecompressionSessionCreate(kCFAllocatorDefault, newFormat, specification.get(),
			attributes.get(), &record, &session);

		if (status != noErr) {
			RTC_LOG(LS_INFO) << name << ": no session, status " << status;
			session = nullptr;

			return false;
		}

		if (!IsUsingHardware(session, kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder)) {
			RTC_LOG(LS_INFO) << name << ": the session does not decode in hardware";

			VTDecompressionSessionInvalidate(session);
			CFRelease(session);
			session = nullptr;

			return false;
		}

		if (softwareFallback) {
			sessionCount++;
		}

		fullRange = newFullRange;

		const CMVideoDimensions size = CMVideoFormatDescriptionGetDimensions(newFormat);

		RTC_LOG(LS_INFO) << name << ": " << size.width << "x" << size.height
			<< (fullRange ? ", full range" : ", studio range");

		return true;
	}

	void VTVideoDecoder::DestroySession()
	{
		if (session != nullptr) {
			VTDecompressionSessionWaitForAsynchronousFrames(session);
			VTDecompressionSessionInvalidate(session);
			CFRelease(session);
			session = nullptr;

			if (softwareFallback) {
				sessionCount--;
			}
		}
		if (format != nullptr) {
			CFRelease(format);
			format = nullptr;
		}

		fullRange = false;
	}

	int32_t VTVideoDecoder::DecodeSample(std::span<const uint8_t> data, const webrtc::EncodedImage & image,
		int64_t renderTimeMs)
	{
		if (callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		if (session == nullptr) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		ScopedCF<CMBlockBufferRef> block;
		size_t size = data.size();

		OSStatus status = CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, nullptr, size,
			kCFAllocatorDefault, nullptr, 0, size, kCMBlockBufferAssureMemoryNowFlag, block.receive());

		if (status == noErr) {
			status = CMBlockBufferReplaceDataBytes(data.data(), block.get(), 0, size);
		}

		ScopedCF<CMSampleBufferRef> sample;

		if (status == noErr) {
			CMSampleTimingInfo timing = { CMTimeMake(1, 30), CMTimeMake(sampleCount++, 30), kCMTimeInvalid };

			status = CMSampleBufferCreateReady(kCFAllocatorDefault, block.get(), format, 1, 1, &timing, 1, &size,
				sample.receive());
		}

		Output output;

		if (status == noErr) {
			VTDecodeInfoFlags info = 0;

			status = VTDecompressionSessionDecodeFrame(session, sample.get(), kVTDecodeFrame_1xRealTimePlayback,
				&output, &info);
		}
		if (status == noErr) {
			// The frame is out before this returns, unless VideoToolbox
			// decides to work asynchronously after all.
			status = VTDecompressionSessionWaitForAsynchronousFrames(session);
		}
		if (status == noErr) {
			status = output.status;
		}

		ScopedCF<CVImageBufferRef> pixels(output.image);

		if (status != noErr) {
			return Fail(status);
		}

		consecutiveErrors = 0;

		// A frame that is not shown, or that VideoToolbox dropped, has no
		// picture.
		if (pixels) {
			Deliver(image, renderTimeMs, pixels.get());
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VTVideoDecoder::Fail(OSStatus status)
	{
		RTC_LOG(LS_WARNING) << name << " failed, status " << status;

		requireKeyFrame = true;

		if (status == kVTInvalidSessionErr) {
			DestroySession();
		}
		if (++consecutiveErrors >= kMaxConsecutiveErrors && softwareFallback) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// WebRTC asks the sender for a key frame.
		return WEBRTC_VIDEO_CODEC_ERROR;
	}

	int32_t VTVideoDecoder::Unsupported()
	{
		requireKeyFrame = true;

		return softwareFallback ? WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE : WEBRTC_VIDEO_CODEC_ERROR;
	}

	void VTVideoDecoder::OnOutput(void * decoder, void * frame, OSStatus status, VTDecodeInfoFlags flags,
		CVImageBufferRef image, CMTime presentationTime, CMTime duration)
	{
		Output * output = static_cast<Output *>(frame);
		output->status = status;

		if (status == noErr && image != nullptr) {
			output->image = static_cast<CVImageBufferRef>(const_cast<void *>(CFRetain(image)));
		}
	}

	void VTVideoDecoder::Deliver(const webrtc::EncodedImage & image, int64_t renderTimeMs, CVImageBufferRef pixels)
	{
		// The buffer holds on to the pixels; WebRTC's frame holds on to the
		// buffer, and converts to I420 only where a consumer needs it.
		RTC_OBJC_TYPE(RTCCVPixelBuffer) * pixelBuffer =
			[[RTC_OBJC_TYPE(RTCCVPixelBuffer) alloc] initWithPixelBuffer:pixels];

		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer =
			webrtc::make_ref_counted<webrtc::ObjCFrameBuffer>(pixelBuffer);

		[pixelBuffer release];

		webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(buffer)
			.set_rtp_timestamp(image.RtpTimestamp())
			.set_timestamp_ms(renderTimeMs)
			.set_ntp_time_ms(image.ntp_time_ms_)
			.set_rotation(image.rotation_)
			.build();

		callback->Decoded(frame, std::nullopt, std::nullopt);
	}
}
