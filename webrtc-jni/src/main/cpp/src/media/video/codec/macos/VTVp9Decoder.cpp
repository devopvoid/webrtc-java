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

#include "media/video/codec/macos/VTVp9Decoder.h"

#include "api/make_ref_counted.h"
#include "api/video/video_frame.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "sdk/objc/components/video_frame_buffer/RTCCVPixelBuffer.h"
#include "sdk/objc/native/src/objc_frame_buffer.h"

#include <atomic>
#include <span>

namespace jni
{
	namespace
	{
		// The sizes the hardware decoder is used for. Outside them the
		// software decoder takes over. Chromium uses the same limits for
		// VP9 on VideoToolbox; the lower one is that of Apple silicon.
		constexpr int kMinSize = 64;
		constexpr int kMaxSize = 4096;

		// A decoder that fails this often in a row, each time on a frame
		// WebRTC then replaced by a key frame, is given up on.
		constexpr int kMaxConsecutiveErrors = 3;

		// How many sessions the process keeps at a time. The media engine
		// serves a limited number of streams; a stream past the limit
		// starts in software. The number is a cautious guess.
		constexpr int kMaxSessions = 8;

		std::atomic<int> sessionCount(0);

		// Releases a Core Foundation object when it goes out of scope.
		template <typename T>
		class ScopedCF
		{
			public:
				ScopedCF() : ref(nullptr) {}
				explicit ScopedCF(T ref) : ref(ref) {}
				~ScopedCF() { if (ref) CFRelease(ref); }

				ScopedCF(const ScopedCF &) = delete;
				ScopedCF & operator=(const ScopedCF &) = delete;

				T get() const { return ref; }
				T * receive() { return &ref; }
				explicit operator bool() const { return ref != nullptr; }

				// Gives up ownership.
				T release() { T result = ref; ref = nullptr; return result; }

			private:
				T ref;
		};

		ScopedCF<CFMutableDictionaryRef> CreateDictionary()
		{
			return ScopedCF<CFMutableDictionaryRef>(CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
				&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks));
		}

		// How a colour space is written into the VP9 configuration box, as
		// its code points, and into the format description.
		struct ColorInfo
		{
			uint8_t primaries;
			uint8_t transfer;
			uint8_t matrix;
			CFStringRef cmPrimaries;
			CFStringRef cmTransfer;
			CFStringRef cmMatrix;
		};

		ColorInfo GetColorInfo(webrtc::Vp9ColorSpace colorSpace)
		{
			switch (colorSpace) {
				case webrtc::Vp9ColorSpace::CS_BT_601:
				case webrtc::Vp9ColorSpace::CS_SMPTE_170:
					return { 6, 6, 6, kCMFormatDescriptionColorPrimaries_SMPTE_C,
						kCMFormatDescriptionTransferFunction_ITU_R_709_2, kCMFormatDescriptionYCbCrMatrix_ITU_R_601_4 };

				case webrtc::Vp9ColorSpace::CS_BT_709:
					return { 1, 1, 1, kCMFormatDescriptionColorPrimaries_ITU_R_709_2,
						kCMFormatDescriptionTransferFunction_ITU_R_709_2, kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2 };

				case webrtc::Vp9ColorSpace::CS_SMPTE_240:
					return { 7, 7, 7, kCMFormatDescriptionColorPrimaries_SMPTE_C,
						kCMFormatDescriptionTransferFunction_SMPTE_240M_1995,
						kCMFormatDescriptionYCbCrMatrix_SMPTE_240M_1995 };

				case webrtc::Vp9ColorSpace::CS_BT_2020:
					return { 9, 14, 9, kCMFormatDescriptionColorPrimaries_ITU_R_2020,
						kCMFormatDescriptionTransferFunction_ITU_R_2020, kCMFormatDescriptionYCbCrMatrix_ITU_R_2020 };

				default:
					// Not signalled in the stream: unspecified.
					return { 2, 2, 2, nullptr, nullptr, nullptr };
			}
		}

		// The format description of a VP9 stream: its dimensions, the VP9
		// configuration box ("vpcC") and the colour properties.
		CMVideoFormatDescriptionRef CreateFormat(int width, int height, bool fullRange,
			webrtc::Vp9ColorSpace colorSpace)
		{
			const ColorInfo color = GetColorInfo(colorSpace);

			// Version 1 and flags 0 come first: VideoToolbox rejects the
			// box without them. Then the profile, a level that covers the
			// sizes in use, the bit depth, 4:2:0 co-located with luma (1)
			// and the range, the colour code points, and no initialization
			// data.
			const uint8_t box[] = {
				1, 0, 0, 0,
				0,
				51,
				static_cast<uint8_t>((8 << 4) | (1 << 1) | (fullRange ? 1 : 0)),
				color.primaries, color.transfer, color.matrix,
				0, 0
			};

			ScopedCF<CFDataRef> boxData(CFDataCreate(kCFAllocatorDefault, box, sizeof(box)));
			ScopedCF<CFMutableDictionaryRef> atoms = CreateDictionary();
			ScopedCF<CFMutableDictionaryRef> extensions = CreateDictionary();

			if (!boxData || !atoms || !extensions) {
				return nullptr;
			}

			CFDictionarySetValue(atoms.get(), CFSTR("vpcC"), boxData.get());
			CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms,
				atoms.get());
			CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_FullRangeVideo,
				fullRange ? kCFBooleanTrue : kCFBooleanFalse);

			if (color.cmPrimaries != nullptr) {
				CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_ColorPrimaries, color.cmPrimaries);
				CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_TransferFunction, color.cmTransfer);
				CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_YCbCrMatrix, color.cmMatrix);
			}

			CMVideoFormatDescriptionRef format = nullptr;
			OSStatus status = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_VP9, width,
				height, extensions.get(), &format);

			if (status != noErr) {
				RTC_LOG(LS_WARNING) << "VideoToolbox VP9 format description failed, status " << status;
				return nullptr;
			}

			return format;
		}
	}

	bool VTVp9Decoder::StreamConfig::operator==(const StreamConfig & other) const
	{
		return width == other.width && height == other.height && fullRange == other.fullRange
			&& colorSpace == other.colorSpace;
	}

	VTVp9Decoder::VTVp9Decoder() :
		callback(nullptr),
		session(nullptr),
		format(nullptr),
		requireKeyFrame(false),
		consecutiveErrors(0),
		sampleCount(0)
	{
	}

	VTVp9Decoder::~VTVp9Decoder()
	{
		Release();
	}

	bool VTVp9Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecVP9) {
			return false;
		}

		Release();

		// The session waits for the first key frame, which tells what to
		// create it for.
		return true;
	}

	int32_t VTVp9Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		// A frame with spatial layers reaches the decoder with its layers
		// back to back and no superframe index, which VideoToolbox does not
		// decode. libvpx does.
		if (image.SpatialIndex().value_or(0) > 0 || image.SpatialLayerFrameSize(1).has_value()) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		const std::optional<webrtc::Vp9UncompressedHeader> header =
			webrtc::ParseUncompressedVp9Header(std::span<const uint8_t>(image.data(), image.size()));

		if (!header) {
			return Fail(kVTVideoDecoderBadDataErr);
		}

		if (image.FrameType() == webrtc::VideoFrameType::kVideoFrameKey) {
			StreamConfig config;

			if (!ReadConfig(*header, config) || !EnsureSession(config)) {
				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			requireKeyFrame = false;
		}
		else {
			if (session == nullptr || requireKeyFrame) {
				// Nothing to build on: WebRTC asks the sender for a key frame.
				return WEBRTC_VIDEO_CODEC_ERROR;
			}

			// Another size without a key frame, by reference scaling, is not
			// something the session takes.
			if (!header->show_existing_frame && header->frame_width != 0
				&& (header->frame_width != active.width || header->frame_height != active.height))
			{
				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}
		}

		Output output;
		OSStatus status = DecodeSample(image, output);

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

	int32_t VTVp9Decoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VTVp9Decoder::Release()
	{
		DestroySession();

		requireKeyFrame = false;
		consecutiveErrors = 0;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo VTVp9Decoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = ImplementationName();
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * VTVp9Decoder::ImplementationName() const
	{
		return "VideoToolbox (VP9)";
	}

	void VTVp9Decoder::OnOutput(void * decoder, void * frame, OSStatus status, VTDecodeInfoFlags flags,
		CVImageBufferRef image, CMTime presentationTime, CMTime duration)
	{
		Output * output = static_cast<Output *>(frame);
		output->status = status;

		if (status == noErr && image != nullptr) {
			output->image = static_cast<CVImageBufferRef>(const_cast<void *>(CFRetain(image)));
		}
	}

	bool VTVp9Decoder::ReadConfig(const webrtc::Vp9UncompressedHeader & header, StreamConfig & config)
	{
		// The negotiated format is profile 0, 8 bit 4:2:0. Check, rather than
		// trust it: a stream that is something else is not for this decoder.
		if (header.profile != 0 || header.bit_detph != webrtc::Vp9BitDept::k8Bit) {
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: profile " << header.profile << " is not decoded in hardware";
			return false;
		}
		if (header.sub_sampling && *header.sub_sampling != webrtc::Vp9YuvSubsampling::k420) {
			return false;
		}
		if (header.frame_width < kMinSize || header.frame_height < kMinSize
			|| header.frame_width > kMaxSize || header.frame_height > kMaxSize)
		{
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: " << header.frame_width << "x" << header.frame_height
				<< " is not decoded in hardware";
			return false;
		}

		config.width = header.frame_width;
		config.height = header.frame_height;
		// The range a stream does not state is the studio range, as in libvpx.
		config.fullRange = header.color_range && *header.color_range == webrtc::Vp9ColorRange::kFull;
		config.colorSpace = header.color_space.value_or(webrtc::Vp9ColorSpace::CS_UNKNOWN);

		return true;
	}

	bool VTVp9Decoder::EnsureSession(const StreamConfig & config)
	{
		if (session != nullptr && config == active) {
			return true;
		}

		CMVideoFormatDescriptionRef newFormat = CreateFormat(config.width, config.height, config.fullRange,
			config.colorSpace);

		if (newFormat == nullptr) {
			return false;
		}

		// The pixel format of the output follows the range, so a session does
		// not outlast a change of it. Another size is not accepted in place
		// either, but a change of the colour space may be.
		if (session != nullptr && active.fullRange == config.fullRange
			&& VTDecompressionSessionCanAcceptFormatDescription(session, newFormat))
		{
			CFRelease(format);
			format = newFormat;
			active = config;

			return true;
		}

		// Whatever is still decoding finishes before the session goes.
		DestroySession();

		if (!CreateSession(config, newFormat)) {
			CFRelease(newFormat);

			return false;
		}

		format = newFormat;
		active = config;

		return true;
	}

	bool VTVp9Decoder::CreateSession(const StreamConfig & config, CMVideoFormatDescriptionRef newFormat)
	{
		if (sessionCount.load() >= kMaxSessions) {
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: too many sessions, using the software decoder";
			return false;
		}

		ScopedCF<CFMutableDictionaryRef> specification = CreateDictionary();
		ScopedCF<CFMutableDictionaryRef> attributes = CreateDictionary();
		ScopedCF<CFDictionaryRef> surfaceProperties(CFDictionaryCreate(kCFAllocatorDefault, nullptr, nullptr, 0,
			&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks));

		// The output is 8 bit 4:2:0, in the range of the stream, so that the
		// samples reach WebRTC as the software decoder would give them.
		const int pixelFormat = config.fullRange
			? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
			: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
		ScopedCF<CFNumberRef> pixelFormatNumber(CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &pixelFormat));

		if (!specification || !attributes || !surfaceProperties || !pixelFormatNumber) {
			return false;
		}

		// Only the hardware decoder: where there is none, or it is busy, the
		// session fails to create and the software decoder takes over.
		CFDictionarySetValue(specification.get(), kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder,
			kCFBooleanTrue);
		CFDictionarySetValue(specification.get(), kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder,
			kCFBooleanTrue);

		CFDictionarySetValue(attributes.get(), kCVPixelBufferPixelFormatTypeKey, pixelFormatNumber.get());
		CFDictionarySetValue(attributes.get(), kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties.get());

		VTDecompressionOutputCallbackRecord record = { OnOutput, this };
		OSStatus status = VTDecompressionSessionCreate(kCFAllocatorDefault, newFormat, specification.get(),
			attributes.get(), &record, &session);

		if (status != noErr) {
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: no session, status " << status;
			session = nullptr;

			return false;
		}

		// Do not rely on the requirement alone.
		CFBooleanRef hardware = nullptr;
		bool usingHardware = false;

		if (VTSessionCopyProperty(session, kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
			kCFAllocatorDefault, &hardware) == noErr && hardware != nullptr)
		{
			usingHardware = CFBooleanGetValue(hardware);
			CFRelease(hardware);
		}
		if (!usingHardware) {
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: the session does not decode in hardware";

			VTDecompressionSessionInvalidate(session);
			CFRelease(session);
			session = nullptr;

			return false;
		}

		sessionCount++;

		RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: " << config.width << "x" << config.height
			<< (config.fullRange ? ", full range" : ", studio range");

		return true;
	}

	void VTVp9Decoder::DestroySession()
	{
		if (session != nullptr) {
			VTDecompressionSessionWaitForAsynchronousFrames(session);
			VTDecompressionSessionInvalidate(session);
			CFRelease(session);
			session = nullptr;

			sessionCount--;
		}
		if (format != nullptr) {
			CFRelease(format);
			format = nullptr;
		}

		active = StreamConfig();
	}

	OSStatus VTVp9Decoder::DecodeSample(const webrtc::EncodedImage & image, Output & output)
	{
		// The whole encoded image is one sample, hidden frames and all: a
		// frame that is not shown, given to VideoToolbox on its own, would
		// come out as a picture.
		ScopedCF<CMBlockBufferRef> block;
		size_t size = image.size();

		OSStatus status = CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, nullptr, size,
			kCFAllocatorDefault, nullptr, 0, size, kCMBlockBufferAssureMemoryNowFlag, block.receive());

		if (status == noErr) {
			status = CMBlockBufferReplaceDataBytes(image.data(), block.get(), 0, size);
		}

		ScopedCF<CMSampleBufferRef> sample;

		if (status == noErr) {
			CMSampleTimingInfo timing = { CMTimeMake(1, 30), CMTimeMake(sampleCount++, 30), kCMTimeInvalid };

			status = CMSampleBufferCreateReady(kCFAllocatorDefault, block.get(), format, 1, 1, &timing, 1, &size,
				sample.receive());
		}
		if (status != noErr) {
			return status;
		}

		VTDecodeInfoFlags info = 0;

		status = VTDecompressionSessionDecodeFrame(session, sample.get(), kVTDecodeFrame_1xRealTimePlayback,
			&output, &info);

		if (status == noErr) {
			// The frame is out before this returns, unless VideoToolbox
			// decides to work asynchronously after all.
			status = VTDecompressionSessionWaitForAsynchronousFrames(session);
		}

		return status;
	}

	int32_t VTVp9Decoder::Fail(OSStatus status)
	{
		RTC_LOG(LS_WARNING) << ImplementationName() << " failed, status " << status;

		requireKeyFrame = true;

		if (status == kVTInvalidSessionErr) {
			DestroySession();
		}
		if (++consecutiveErrors >= kMaxConsecutiveErrors) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// WebRTC asks the sender for a key frame.
		return WEBRTC_VIDEO_CODEC_ERROR;
	}

	void VTVp9Decoder::Deliver(const webrtc::EncodedImage & image, int64_t renderTimeMs, CVImageBufferRef pixels)
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
