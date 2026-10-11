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

#include "media/video/codec/macos/VTH265Encoder.h"
#include "media/video/codec/macos/VTCodecUtils.h"

#include "api/video_codecs/scalability_mode.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "rtc_base/time_utils.h"
#include "sdk/objc/components/video_frame_buffer/RTCCVPixelBuffer.h"
#include "sdk/objc/native/src/objc_frame_buffer.h"
#include "third_party/libyuv/include/libyuv/convert_from.h"

#include <algorithm>
#include <memory>
#include <span>

namespace jni
{
	namespace
	{
		// The rate may go above the average by this much within a second, as
		// WebRTC allows its own VideoToolbox H.264 encoder.
		constexpr double kDataRateLimitFactor = 1.5;

		// Key frames come when WebRTC asks for them; these only bound how
		// long a stream goes without one, as for WebRTC's H.264 encoder.
		constexpr int kMaxKeyFrameInterval = 7200;
		constexpr int kMaxKeyFrameIntervalSeconds = 240;

		constexpr uint8_t kStartCode[] = { 0, 0, 0, 1 };

		void SetProperty(VTCompressionSessionRef session, CFStringRef key, CFTypeRef value)
		{
			OSStatus status = VTSessionSetProperty(session, key, value);

			if (status != noErr) {
				RTC_LOG(LS_INFO) << "VideoToolbox H.265 encoder: a property is not supported, status " << status;
			}
		}

		void SetIntProperty(VTCompressionSessionRef session, CFStringRef key, int64_t value)
		{
			ScopedCF<CFNumberRef> number(CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt64Type, &value));

			if (number) {
				SetProperty(session, key, number.get());
			}
		}

		bool IsKeyFrame(CMSampleBufferRef sample)
		{
			CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);

			if (attachments == nullptr || CFArrayGetCount(attachments) == 0) {
				return true;
			}

			auto attachment = static_cast<CFDictionaryRef>(CFArrayGetValueAtIndex(attachments, 0));

			return !CFDictionaryContainsKey(attachment, kCMSampleAttachmentKey_NotSync);
		}
	}

	VTH265Encoder::VTH265Encoder(const webrtc::SdpVideoFormat & format) :
		codecSettings(),
		bitrateBps(0),
		framerate(30),
		session(nullptr),
		callback(nullptr),
		failed(false),
		lastTimestampUs(-1),
		outputProcessor(webrtc::kVideoCodecH265, format)
	{
	}

	VTH265Encoder::~VTH265Encoder()
	{
		Release();
	}

	int32_t VTH265Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// A single layer; there is no software encoder for anything else.
		if (settings->GetScalabilityMode().value_or(webrtc::ScalabilityMode::kL1T1) != webrtc::ScalabilityMode::kL1T1) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		// 4:2:0 has chroma at half the resolution in both directions. The
		// first frame of a source can be odd, since WebRTC asks the source
		// for requested_resolution_alignment only once the encoder has been
		// set up, so the encoder takes the even size below it and drops the
		// last row and column of such frames.
		if (settings->width < 2 || settings->height < 2) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		codecSettings = *settings;
		codecSettings.width &= ~1;
		codecSettings.height &= ~1;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);

		if (!CreateSession()) {
			Release();

			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		RTC_LOG(LS_INFO) << "VideoToolbox H.265 encoder initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps";

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VTH265Encoder::CreateSession()
	{
		ScopedCF<CFMutableDictionaryRef> specification = CreateCFDictionary();
		ScopedCF<CFMutableDictionaryRef> attributes = CreateCFDictionary();
		ScopedCF<CFDictionaryRef> surfaceProperties(CFDictionaryCreate(kCFAllocatorDefault, nullptr, nullptr, 0,
			&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks));

		if (!specification || !attributes || !surfaceProperties) {
			return false;
		}

		// Only the hardware encoder: where there is none, or it is busy, the
		// session fails to create, and WebRTC goes on with the next codec.
		CFDictionarySetValue(specification.get(), kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder,
			kCFBooleanTrue);
		CFDictionarySetValue(specification.get(), kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder,
			kCFBooleanTrue);

		// The buffers of the pool, which frames that are not in a
		// CVPixelBuffer already are converted into. Studio range, as WebRTC's
		// I420 frames are.
		if (!SetCFNumber(attributes.get(), kCVPixelBufferPixelFormatTypeKey,
				kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
			|| !SetCFNumber(attributes.get(), kCVPixelBufferWidthKey, codecSettings.width)
			|| !SetCFNumber(attributes.get(), kCVPixelBufferHeightKey, codecSettings.height))
		{
			return false;
		}

		CFDictionarySetValue(attributes.get(), kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties.get());

		OSStatus status = VTCompressionSessionCreate(kCFAllocatorDefault, codecSettings.width, codecSettings.height,
			kCMVideoCodecType_HEVC, specification.get(), attributes.get(), nullptr, OnOutput, this, &session);

		if (status != noErr) {
			RTC_LOG(LS_INFO) << "VideoToolbox H.265 encoder: no session, status " << status;
			session = nullptr;

			return false;
		}

		if (!IsUsingHardware(session, kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder)) {
			RTC_LOG(LS_INFO) << "VideoToolbox H.265 encoder: the session does not encode in hardware";
			return false;
		}

		SetProperty(session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
		SetProperty(session, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_HEVC_Main_AutoLevel);
		SetProperty(session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
		SetIntProperty(session, kVTCompressionPropertyKey_MaxKeyFrameInterval, kMaxKeyFrameInterval);
		SetIntProperty(session, kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, kMaxKeyFrameIntervalSeconds);

		ConfigureRates();

		status = VTCompressionSessionPrepareToEncodeFrames(session);

		if (status != noErr) {
			RTC_LOG(LS_WARNING) << "VideoToolbox H.265 encoder failed to prepare, status " << status;
			return false;
		}

		return true;
	}

	void VTH265Encoder::ConfigureRates()
	{
		SetIntProperty(session, kVTCompressionPropertyKey_AverageBitRate, bitrateBps);
		SetIntProperty(session, kVTCompressionPropertyKey_ExpectedFrameRate, framerate);

		// Bytes over a duration in seconds.
		const int64_t bytes = static_cast<int64_t>(bitrateBps * kDataRateLimitFactor / 8);
		const double seconds = 1.0;

		ScopedCF<CFNumberRef> bytesNumber(CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt64Type, &bytes));
		ScopedCF<CFNumberRef> secondsNumber(CFNumberCreate(kCFAllocatorDefault, kCFNumberDoubleType, &seconds));

		if (bytesNumber && secondsNumber) {
			const void * values[] = { bytesNumber.get(), secondsNumber.get() };
			ScopedCF<CFArrayRef> limits(CFArrayCreate(kCFAllocatorDefault, values, 2, &kCFTypeArrayCallBacks));

			if (limits) {
				SetProperty(session, kVTCompressionPropertyKey_DataRateLimits, limits.get());
			}
		}
	}

	int32_t VTH265Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VTH265Encoder::Release()
	{
		DestroySession();

		failed = false;
		lastTimestampUs = -1;

		// No output arrives any more.
		outputProcessor.Reset();

		return WEBRTC_VIDEO_CODEC_OK;
	}

	void VTH265Encoder::DestroySession()
	{
		if (session == nullptr) {
			return;
		}

		// The frames still in the session come out, and are handed on, before
		// it goes; their pending entries go with them.
		VTCompressionSessionCompleteFrames(session, kCMTimeInvalid);
		VTCompressionSessionInvalidate(session);
		CFRelease(session);

		session = nullptr;
	}

	int32_t VTH265Encoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		webrtc::EncodedImageCallback * encodeCallback = callback.load();

		if (session == nullptr || encodeCallback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		// There is no software encoder to go on with: an error makes WebRTC
		// switch to the next codec.
		if (failed) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		bool keyFrame = false;

		if (frameTypes != nullptr) {
			for (webrtc::VideoFrameType type : *frameTypes) {
				keyFrame |= type == webrtc::VideoFrameType::kVideoFrameKey;
			}
		}

		ScopedCF<CVPixelBufferRef> pixelBuffer(CreatePixelBuffer(frame));

		if (!pixelBuffer) {
			RTC_LOG(LS_WARNING) << "VideoToolbox H.265 encoder: no pixel buffer for a frame";

			encodeCallback->OnFrameDropped(frame.rtp_timestamp(), 0, true);

			return WEBRTC_VIDEO_CODEC_OK;
		}

		ScopedCF<CFMutableDictionaryRef> frameProperties(keyFrame ? CreateCFDictionary().release() : nullptr);

		if (frameProperties) {
			CFDictionarySetValue(frameProperties.get(), kVTEncodeFrameOptionKey_ForceKeyFrame, kCFBooleanTrue);
		}

		// Rate control goes by the presentation times, which have to rise.
		int64_t timestampUs = frame.timestamp_us();

		if (timestampUs <= lastTimestampUs) {
			timestampUs = lastTimestampUs + 1;
		}

		lastTimestampUs = timestampUs;

		// The output callback owns it. Should the session reject the frame
		// without calling back, it is lost, as with WebRTC's H.264 encoder,
		// rather than freed twice.
		auto * pending = new PendingFrame {
			this,
			frame.rtp_timestamp(),
			frame.render_time_ms(),
			frame.ntp_time_ms(),
			frame.rotation()
		};

		OSStatus status = VTCompressionSessionEncodeFrame(session, pixelBuffer.get(),
			CMTimeMake(timestampUs, webrtc::kNumMicrosecsPerSec), kCMTimeInvalid, frameProperties.get(),
			pending, nullptr);

		if (status != noErr) {
			RTC_LOG(LS_WARNING) << "VideoToolbox H.265 encoder rejected a frame, status " << status;

			failed = true;

			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	CVPixelBufferRef VTH265Encoder::CreatePixelBuffer(const webrtc::VideoFrame & frame)
	{
		const int width = static_cast<int>(codecSettings.width);
		const int height = static_cast<int>(codecSettings.height);

		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = frame.video_frame_buffer();

		CVPixelBufferPoolRef pool = VTCompressionSessionGetPixelBufferPool(session);

		if (pool == nullptr) {
			return nullptr;
		}

		// The native buffers on macOS are those of the camera and of the
		// VideoToolbox decoders.
		if (buffer->type() == webrtc::VideoFrameBuffer::Type::kNative) {
			id<RTC_OBJC_TYPE(RTCVideoFrameBuffer)> objcBuffer =
				static_cast<webrtc::ObjCFrameBuffer *>(buffer.get())->wrapped_frame_buffer();

			if ([objcBuffer isKindOfClass:[RTC_OBJC_TYPE(RTCCVPixelBuffer) class]]) {
				RTC_OBJC_TYPE(RTCCVPixelBuffer) * pixelBuffer = (RTC_OBJC_TYPE(RTCCVPixelBuffer) *) objcBuffer;

				// The session scales a buffer of another size itself.
				if (![pixelBuffer requiresCropping]) {
					return CVPixelBufferRetain(pixelBuffer.pixelBuffer);
				}

				CVPixelBufferRef output = nullptr;

				if (CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &output) != kCVReturnSuccess) {
					return nullptr;
				}

				if ([pixelBuffer requiresScalingToWidth:width height:height]) {
					scaleBuffer.resize([pixelBuffer bufferSizeForCroppingAndScalingToWidth:width height:height]);
				}

				if (![pixelBuffer cropAndScaleTo:output withTempBuffer:scaleBuffer.data()]) {
					CVPixelBufferRelease(output);
					return nullptr;
				}

				return output;
			}
		}

		// An odd frame is cut to the even size of the session, anything else
		// of another size is scaled to it.
		if (buffer->width() < width || buffer->height() < height
			|| buffer->width() > width + 1 || buffer->height() > height + 1)
		{
			buffer = buffer->Scale(width, height);
		}

		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420 = buffer->ToI420();

		if (!i420) {
			return nullptr;
		}

		CVPixelBufferRef output = nullptr;

		if (CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &output) != kCVReturnSuccess) {
			return nullptr;
		}
		if (CVPixelBufferLockBaseAddress(output, 0) != kCVReturnSuccess) {
			CVPixelBufferRelease(output);
			return nullptr;
		}

		int result = libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
			i420->DataV(), i420->StrideV(),
			static_cast<uint8_t *>(CVPixelBufferGetBaseAddressOfPlane(output, 0)),
			static_cast<int>(CVPixelBufferGetBytesPerRowOfPlane(output, 0)),
			static_cast<uint8_t *>(CVPixelBufferGetBaseAddressOfPlane(output, 1)),
			static_cast<int>(CVPixelBufferGetBytesPerRowOfPlane(output, 1)),
			width, height);

		CVPixelBufferUnlockBaseAddress(output, 0);

		if (result != 0) {
			CVPixelBufferRelease(output);
			return nullptr;
		}

		return output;
	}

	void VTH265Encoder::SetRates(const RateControlParameters & parameters)
	{
		const uint32_t bitrate = parameters.bitrate.get_sum_bps();

		// A zero bitrate pauses the stream; the encoder gets no frames then.
		if (bitrate == 0) {
			return;
		}

		bitrateBps = bitrate;

		if (parameters.framerate_fps >= 1.0) {
			framerate = static_cast<uint32_t>(parameters.framerate_fps + 0.5);
		}

		if (session != nullptr) {
			ConfigureRates();
		}
	}

	webrtc::VideoEncoder::EncoderInfo VTH265Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = "VideoToolbox (H.265)";
		info.is_hardware_accelerated = true;
		// CVPixelBuffers are taken as they are.
		info.supports_native_handle = true;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		// 4:2:0 needs even dimensions.
		info.requested_resolution_alignment = 2;

		return info;
	}

	void VTH265Encoder::OnOutput(void * encoder, void * frame, OSStatus status, VTEncodeInfoFlags flags,
		CMSampleBufferRef sample)
	{
		std::unique_ptr<PendingFrame> pending(static_cast<PendingFrame *>(frame));

		if (pending == nullptr) {
			return;
		}

		VTH265Encoder * self = pending->encoder;

		if (status != noErr) {
			RTC_LOG(LS_WARNING) << "VideoToolbox H.265 encoder failed, status " << status;

			self->failed = true;
			return;
		}

		webrtc::EncodedImageCallback * encodeCallback = self->callback.load();

		if ((flags & kVTEncodeInfo_FrameDropped) || sample == nullptr) {
			if (encodeCallback != nullptr) {
				encodeCallback->OnFrameDropped(pending->rtpTimestamp, 0, true);
			}
			return;
		}

		self->DeliverOutput(*pending, sample);
	}

	void VTH265Encoder::DeliverOutput(const PendingFrame & pending, CMSampleBufferRef sample)
	{
		CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sample);
		CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);

		if (format == nullptr || block == nullptr) {
			failed = true;
			return;
		}

		const bool keyFrame = IsKeyFrame(sample);
		size_t parameterSetCount = 0;
		int lengthSize = 0;

		OSStatus status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(format, 0, nullptr, nullptr,
			&parameterSetCount, &lengthSize);

		if (status != noErr || lengthSize < 1 || lengthSize > 4) {
			RTC_LOG(LS_WARNING) << "VideoToolbox H.265 encoder: no parameter sets, status " << status;
			failed = true;
			return;
		}

		annexB.clear();

		// The parameter sets are in the format description only; a receiver
		// needs them in front of each key frame.
		if (keyFrame) {
			for (size_t i = 0; i < parameterSetCount; i++) {
				const uint8_t * parameterSet = nullptr;
				size_t size = 0;

				status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(format, i, &parameterSet, &size,
					nullptr, nullptr);

				if (status != noErr) {
					failed = true;
					return;
				}

				annexB.insert(annexB.end(), std::begin(kStartCode), std::end(kStartCode));
				annexB.insert(annexB.end(), parameterSet, parameterSet + size);
			}
		}

		// The NAL units come with length prefixes, which become start codes.
		const size_t length = CMBlockBufferGetDataLength(block);
		std::vector<uint8_t> data(length);

		if (CMBlockBufferCopyDataBytes(block, 0, length, data.data()) != kCMBlockBufferNoErr) {
			failed = true;
			return;
		}

		size_t position = 0;

		while (position + lengthSize <= length) {
			size_t naluSize = 0;

			for (int i = 0; i < lengthSize; i++) {
				naluSize = (naluSize << 8) | data[position++];
			}

			if (naluSize > length - position) {
				RTC_LOG(LS_WARNING) << "VideoToolbox H.265 encoder: a NAL unit runs past the frame";
				failed = true;
				return;
			}

			annexB.insert(annexB.end(), std::begin(kStartCode), std::end(kStartCode));
			annexB.insert(annexB.end(), data.begin() + position, data.begin() + position + naluSize);

			position += naluSize;
		}

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(pending.rtpTimestamp);
		image.capture_time_ms_ = pending.captureTimeMs;
		image.ntp_time_ms_ = pending.ntpTimeMs;
		image.rotation_ = pending.rotation;

		webrtc::CodecSpecificInfo info;

		if (!outputProcessor.Process(annexB, keyFrame, image, info)) {
			failed = true;
			return;
		}

		webrtc::EncodedImageCallback * encodeCallback = callback.load();

		if (encodeCallback != nullptr) {
			encodeCallback->OnEncodedImage(image, &info);
		}
	}
}
